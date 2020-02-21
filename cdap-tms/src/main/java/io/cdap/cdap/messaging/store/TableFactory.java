/*
 * Copyright © 2016-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.messaging.store;

import io.cdap.cdap.messaging.TopicMetadata;

import java.io.IOException;

/**
 * A factory to create instances of {@link MessageTable}, {@link PayloadTable} and {@link MetadataTable}.
 */
public interface TableFactory {

  MetadataTable createMetadataTable() throws IOException;

  MessageTable createMessageTable(TopicMetadata topicMetadata) throws IOException;

  PayloadTable createPayloadTable(TopicMetadata topicMetadata) throws IOException;

  default void close() {
    // no-op
  }
}
