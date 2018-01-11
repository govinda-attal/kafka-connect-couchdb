/*
 * Copyright 2018 Xebia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xebia.kafka.connect.couchdb;

import com.xebia.kafka.connect.couchdb.converting.Converter;
import com.xebia.kafka.connect.couchdb.merging.Merger;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava.core.Vertx;
import io.vertx.rxjava.core.buffer.Buffer;
import io.vertx.rxjava.ext.web.client.HttpResponse;
import io.vertx.rxjava.ext.web.client.WebClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.xebia.kafka.connect.couchdb.CouchDBConnectorConfig.Constants.*;
import static com.xebia.kafka.connect.couchdb.CouchDBConnectorConfig.MAX_CONFLICTING_DOCS_FETCH_RETRIES_CONFIG;

public class CouchDBSinkTask extends SinkTask {
  private class LatestRevAndConflicts {
    JsonObject latestRev;
    String[] conflicts;

    LatestRevAndConflicts(JsonObject latestRev, String[] conflicts) {
      this.latestRev = latestRev;
      this.conflicts = conflicts;
    }
  }

  private class LatestRevAndConflictDocs {
    JsonObject latestRev;
    List<JsonObject> conflictDocs;

    LatestRevAndConflictDocs(JsonObject latestRev, List<JsonObject> conflictDocs) {
      this.latestRev = latestRev;
      this.conflictDocs = conflictDocs;
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(CouchDBSinkTask.class);

  private String auth;
  private Map<String, String> databasesMapping;
  private Converter converter;
  private Merger merger;
  private int maxConflictingDocsFetchRetries;

  private WebClient httpClient;

  private HttpResponse<Buffer> validateResponse(HttpResponse<Buffer> response, int... acceptedStatuses) {
    boolean statusNotAccepted = true;
    for (int as : acceptedStatuses) {
      if (response.statusCode() == as) {
        statusNotAccepted = false;
      }
    }
    boolean statusNotOk = response.statusCode() >= 200 || response.statusCode() < 400;

    if (statusNotOk && statusNotAccepted) {
      throw new RuntimeException(
        "Response contained error status: " +
          "\nCode:    " + response.statusCode() +
          "\nMessage: " + response.statusMessage()
      );
    }

    if (response.body().length() == 0) {
      throw new RuntimeException("Response did not contain a valid body");
    }

    return response;
  }

  private Observable<LatestRevAndConflicts> fetchLatestRevAndConflicts(String dbName, String id) {
    return httpClient
      .get("/" + dbName + "/" + id + "?conflicts=true")
      .putHeader(AUTH_HEADER, auth)
      .rxSend()
      .map(this::validateResponse)
      .map(response -> {
        JsonObject doc = response.bodyAsJsonObject();
        JsonArray conflictsArr = doc.getJsonArray("_conflicts");

        String[] conflicts = new String[conflictsArr.size()];
        for (int i = 0; i < conflictsArr.size(); i++) {
          conflicts[i] = conflictsArr.getString(i);
        }

        return new LatestRevAndConflicts(doc, conflicts);
      })
      .toObservable();
  }

  private Observable<JsonObject> fetchDocForRev(String dbName, String id, String _rev) {
    return httpClient
      .get("/" + dbName + "/" + id + "?rev=" + _rev)
      .putHeader(AUTH_HEADER, auth)
      .rxSend()
      .map(this::validateResponse)
      .map(HttpResponse::bodyAsJsonObject)
      .toObservable();
  }

  private Observable<LatestRevAndConflictDocs> fetchLatestRevAndConflictingDocs(
    String dbName,
    String id,
    LatestRevAndConflicts latestRevAndConflicts
  ) {
    return Observable
      .from(latestRevAndConflicts.conflicts)
      .flatMap(conflict -> fetchDocForRev(dbName, id, conflict))
      .reduce(new ArrayList<JsonObject>(), (acc, doc) -> {
        acc.add(doc);
        return acc;
      })
      .map(conflictingDocs -> new LatestRevAndConflictDocs(latestRevAndConflicts.latestRev, conflictingDocs));
  }

  private Observable<HttpResponse<Buffer>> update(String dbName, String id, JsonObject newDoc) {
    return fetchLatestRevAndConflicts(dbName, id)
      .flatMap(lrac -> fetchLatestRevAndConflictingDocs(dbName, id, lrac))
      .retry(maxConflictingDocsFetchRetries)
      .map(lracd -> {
        newDoc.put("_rev", lracd.latestRev.getString("_rev"));
        return merger.merge(newDoc, lracd.latestRev, lracd.conflictDocs);
      })
      .map(Merger::process)
      .map(Json::encode)
      .flatMap(body -> httpClient
        .post("/" + dbName + "/_bulk_docs")
        .rxSendJson(body)
        .toObservable()
      );
  }

  private Observable<HttpResponse<Buffer>> insert(String dbName, JsonObject newDoc) {
    return httpClient
      .post("/" + dbName)
      .putHeader(AUTH_HEADER, auth)
      .rxSendJsonObject(newDoc)
      .toObservable();
  }

  @Override
  public String version() {
    return VersionUtil.getVersion();
  }

  @Override
  public void start(Map<String, String> properties) {
    CouchDBConnectorConfig config = new CouchDBConnectorConfig(properties);

    auth = config.getBasicAuth();
    databasesMapping = config.getTopicsToDatabasesMapping();
    converter = config.getConverter();
    merger = config.getMerger();
    maxConflictingDocsFetchRetries = config.getInt(MAX_CONFLICTING_DOCS_FETCH_RETRIES_CONFIG);

    httpClient = WebClient.create(Vertx.vertx(), new WebClientOptions(config.getHttpClientOptions()));
  }

  @Override
  public void put(Collection<SinkRecord> records) {
    Observable
      .from(records)
      .map(record -> {
        String topic = record.topic();

        String dbName;
        try {
          dbName = databasesMapping.get(topic);
        } catch (NullPointerException e) {
          LOG.error("No database mapping found for topic {}", topic);
          throw e;
        }

        JsonObject newDoc;
        try {
          newDoc = converter.fromRecord(record);
        } catch (DataException e) {
          LOG.error("Could not fromRecord record to JSON on topic {}", topic);
          throw e;
        }

        String id = newDoc.getString("_id");
        if (id == null) {
          LOG.error(
            "Conversion result JSON from topic {} did not contain a valid _id field\nMessage: {}",
            topic,
            newDoc.encodePrettily()
          );
          throw new RuntimeException("No valid _id field present in conversion result JSON");
        }

        // We try to insert the document as if it was new first
        return insert(dbName, newDoc)
          .map(result -> {
            // A conflict signals a version of this document exists in the database, so we update
            if (result.statusCode() == CONFLICT_STATUS_CODE) {
              return update(dbName, id, newDoc)
                .map(this::validateResponse)
                .map(response -> record);
            }

            // The insertion produced an error other than a conflict error, we cannot handle this document
            else if (result.statusCode() < 200 || result.statusCode() >= 400) {
              LOG.error(
                "Received error response while trying to insert new document" +
                  "\nTopic:    {}" +
                  "\nDatabase: {}" +
                  "\nDocument: {}" +
                  "\nStatus:   {}" +
                  "\nResponse: {}",
                topic,
                dbName,
                newDoc.encodePrettily(),
                result.statusMessage(),
                result.bodyAsString()
              );
              throw new RuntimeException("Server error received while trying to insert new document");
            }

            // The insertion succeeded, the document was newly created in the database
            return record;
          });
      })
      .doOnError(e -> LOG.error("Error occurred while handling record", e))
      .toBlocking();
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
  }

  @Override
  public void stop() {
    httpClient.close();
  }
}