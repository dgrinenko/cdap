/*
 * Copyright © 2017 Cask Data, Inc.
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

package io.cdap.cdap.etl.spark.function;

import io.cdap.cdap.etl.common.RecordInfo;
import io.cdap.cdap.etl.common.RecordType;

import java.util.Collections;
import java.util.Objects;

/**
 * Filters a SparkCollection containing both output and errors to one that just contains output from a specific port.
 *
 * @param <T> type of output record
 */
public class OutputPassFilter<T> implements FlatMapFunc<RecordInfo<Object>, T> {
  private final String port;

  public OutputPassFilter() {
    this(null);
  }

  public OutputPassFilter(String port) {
    this.port = port;
  }

  @Override
  public Iterable<T> call(RecordInfo<Object> input) throws Exception {
    //noinspection unchecked
    return input.getType() == RecordType.OUTPUT && Objects.equals(port, input.getFromPort()) ?
      Collections.singletonList((T) input.getValue()) : Collections.<T>emptyList();
  }
}