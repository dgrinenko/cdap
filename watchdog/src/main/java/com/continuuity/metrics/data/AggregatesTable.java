package com.continuuity.metrics.data;

import com.continuuity.api.common.Bytes;
import com.continuuity.api.data.OperationException;
import com.continuuity.common.utils.ImmutablePair;
import com.continuuity.data.operation.executor.omid.memory.MemoryReadPointer;
import com.continuuity.data.table.OrderedVersionedColumnarTable;
import com.continuuity.data.table.Scanner;
import com.continuuity.metrics.MetricsConstants;
import com.continuuity.metrics.transport.MetricsRecord;
import com.continuuity.metrics.transport.TagMetric;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FuzzyRowFilter;
import org.apache.hadoop.hbase.util.Pair;

import java.util.Iterator;

/**
 * Table for storing aggregated metrics for all time.
 *
 * <p>
 *   Row key:
 *   {@code context|metric|runId}
 * </p>
 * <p>
 *   Column:
 *   {@code tag}
 * </p>
 */
public final class AggregatesTable {

  private final MetricsEntityCodec entityCodec;
  private final OrderedVersionedColumnarTable aggregatesTable;
  private final boolean isFilterable;

  AggregatesTable(OrderedVersionedColumnarTable aggregatesTable, MetricsEntityCodec entityCodec) {
    this.entityCodec = entityCodec;
    this.aggregatesTable = aggregatesTable;
    this.isFilterable = aggregatesTable instanceof FilterableOVCTable;
  }

  /**
   * Updates aggregates for the given list of {@link MetricsRecord}.
   *
   * @throws OperationException When there is error updating the table.
   */
  public void update(Iterable<MetricsRecord> records) throws OperationException {
    update(records.iterator());
  }

  /**
   * Updates aggregates for the given iterator of {@link MetricsRecord}.
   *
   * @throws OperationException When there is error updating the table.
   */
  public void update(Iterator<MetricsRecord> records) throws OperationException {
    while (records.hasNext()) {
      MetricsRecord record = records.next();
      byte[] rowKey = getKey(record.getContext(), record.getName(), record.getRunId());
      byte[][] columns = new byte[record.getTags().size() + 1][];
      long[] increments = new long[record.getTags().size() + 1];
      long writeVersion = System.currentTimeMillis();

      // The no tag value
      columns[0] = Bytes.toBytes(MetricsConstants.EMPTY_TAG);
      increments[0] = record.getValue();

      // For each tag, increments corresponding values
      int idx = 1;
      for (TagMetric tag : record.getTags()) {
        columns[idx] = Bytes.toBytes(tag.getTag());
        increments[idx] = tag.getValue();
        idx++;
      }

      aggregatesTable.increment(rowKey, columns, increments, MemoryReadPointer.DIRTY_READ, writeVersion);
    }
  }

  /**
   * Scans the aggregate table for metrics without tag.
   * @param contextPrefix Prefix of context to match
   * @param metricPrefix Prefix of metric to match
   * @return A {@link AggregatesScanner} for iterating scan over matching rows
   */
  public AggregatesScanner scan(String contextPrefix, String metricPrefix) {
    return scan(contextPrefix, metricPrefix, null, MetricsConstants.EMPTY_TAG);
  }

  /**
   * Scans the aggregate table.
   *
   * @param contextPrefix Prefix of context to match
   * @param metricPrefix Prefix of metric to match
   * @param tagPrefix Prefix of tag to match
   * @return A {@link AggregatesScanner} for iterating scan over matching rows
   */
  public AggregatesScanner scan(String contextPrefix, String metricPrefix, String runId, String tagPrefix) {
    byte[] startRow = getPaddedKey(contextPrefix, metricPrefix, runId, 0);
    byte[] endRow = getPaddedKey(contextPrefix, metricPrefix, runId, 0xff);

    Scanner scanner;
    if (isFilterable) {
      scanner = ((FilterableOVCTable) aggregatesTable).scan(startRow, endRow,
                                                            MemoryReadPointer.DIRTY_READ,
                                                            getFilter(contextPrefix, metricPrefix, runId));
    } else {
      scanner = aggregatesTable.scan(startRow, endRow, MemoryReadPointer.DIRTY_READ);
    }

    return new AggregatesScanner(contextPrefix, metricPrefix, runId,
                                 tagPrefix == null ? MetricsConstants.EMPTY_TAG : tagPrefix, scanner, entityCodec);
  }

  /**
   * Clears the storage table.
   * @throws OperationException If error in clearing data.
   */
  public void clear() throws OperationException {
    aggregatesTable.clear();
  }

  private byte[] getKey(String context, String metric, String runId) {
    Preconditions.checkArgument(context != null, "Context cannot be null.");
    Preconditions.checkArgument(runId != null, "RunId cannot be null.");
    Preconditions.checkArgument(metric != null, "Metric cannot be null.");

    return Bytes.add(entityCodec.encode(MetricsEntityType.CONTEXT, context),
                     entityCodec.encode(MetricsEntityType.METRIC, metric),
                     entityCodec.encode(MetricsEntityType.RUN, runId));

  }

  private byte[] getPaddedKey(String contextPrefix, String metricPrefix, String runId, int padding) {

    Preconditions.checkArgument(metricPrefix != null, "Metric cannot be null.");

    return Bytes.concat(
      entityCodec.paddedEncode(MetricsEntityType.CONTEXT, contextPrefix, padding),
      entityCodec.paddedEncode(MetricsEntityType.METRIC, metricPrefix, padding),
      entityCodec.paddedEncode(MetricsEntityType.RUN, runId, padding)
    );
  }

  private Filter getFilter(String contextPrefix, String metricPrefix, String runId) {
    // Create fuzzy row filter
    ImmutablePair<byte[], byte[]> contextPair = entityCodec.paddedFuzzyEncode(MetricsEntityType.CONTEXT,
                                                                              contextPrefix, 0);
    ImmutablePair<byte[], byte[]> metricPair = entityCodec.paddedFuzzyEncode(MetricsEntityType.METRIC,
                                                                             metricPrefix, 0);
    ImmutablePair<byte[], byte[]> runIdPair = entityCodec.paddedFuzzyEncode(MetricsEntityType.RUN, runId, 0);

    // Use a FuzzyRowFilter to select the row and the use ColumnPrefixFilter to select tag column.
    return new FuzzyRowFilter(ImmutableList.of(Pair.newPair(
      Bytes.concat(contextPair.getFirst(), metricPair.getFirst(), runIdPair.getFirst()),
      Bytes.concat(contextPair.getSecond(), metricPair.getSecond(), runIdPair.getSecond())
    )));
  }
}
