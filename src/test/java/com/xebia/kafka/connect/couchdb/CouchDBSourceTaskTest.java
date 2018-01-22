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

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.storage.Converter;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static com.xebia.kafka.connect.couchdb.TestUtils.TEST_LATEST_REV;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CouchDBSourceTaskTest {
  @Test
  public void createRecordTest() {
    CouchDBSourceTask sourceTask = new CouchDBSourceTask();
    Converter converter = new JsonConverter();
    converter.configure(Collections.singletonMap("schemas.enable", false), false);

    SourceRecord record = sourceTask.createRecord(
      "MyDatabase",
      "MySeq",
      "MyTopic",
      TEST_LATEST_REV,
      converter
    );

    assertEquals(
      "MyDatabase", record.sourcePartition().get("database"),
      "should use given database name as database in source partition"
    );
    assertEquals(
      "MySeq", record.sourceOffset().get("seq"),
      "should use given sequence as seq in source offset"
    );
    assertEquals(
      "MyTopic", record.topic(),
      "should use given topic as topic"
    );
    assertEquals(
      Schema.STRING_SCHEMA, record.keySchema(),
      "should use String schema as key schema"
    );
    assertEquals(
      TEST_LATEST_REV.getString("_id"), record.key(),
      "should use given document's _id field as key"
    );
    assertEquals(
      TEST_LATEST_REV.getString("_id"), ((Map) record.value()).get("_id"),
      "should use given document as value (_id check)"
    );
    assertEquals(
      TEST_LATEST_REV.getString("_rev"), ((Map) record.value()).get("_rev"),
      "should use given document as value (_rev check)"
    );
    assertEquals(
      TEST_LATEST_REV.getString("bar"), ((Map) record.value()).get("bar"),
      "should use given document as value (bar check)"
    );
  }
}
