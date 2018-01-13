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

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class TestUtils {
  public static CouchDBConnectorConfig createConfig(String ...configEntries) {
    Properties props = new Properties();
    try {
      props.load(TestUtils.class.getClassLoader().getResourceAsStream("couchdb-sink.properties"));
    } catch (IOException e) {
      throw new RuntimeException("Could not load properties file");
    }

    Map<String, String> configMap = new HashMap<>();

    Enumeration<?> propNames = props.propertyNames();
    while (propNames.hasMoreElements()) {
      String key = (String) propNames.nextElement();
      configMap.put(key, props.getProperty(key));
    }

    for (String configEntry : configEntries) {
      String[] keyValue = configEntry.split("=");
      configMap.put(keyValue[0], keyValue[1]);
    }

    return new CouchDBConnectorConfig(configMap);
  }
}