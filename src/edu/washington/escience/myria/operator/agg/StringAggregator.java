package edu.washington.escience.myria.operator.agg;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.math.LongMath;

import edu.washington.escience.myria.Schema;
import edu.washington.escience.myria.Type;
import edu.washington.escience.myria.storage.AppendableTable;
import edu.washington.escience.myria.storage.ReadableColumn;
import edu.washington.escience.myria.storage.ReadableTable;

/**
 * Knows how to compute some aggregate over a StringColumn.
 */
public final class StringAggregator implements PrimitiveAggregator {

  /** Required for Java serialization. */
  private static final long serialVersionUID = 1L;

  /**
   * Aggregate operations. A set of all valid aggregation operations, i.e. those in
   * {@link StringAggregator#AVAILABLE_AGG}.
   * 
   * Note that we use a {@link LinkedHashSet} to ensure that the iteration order is consistent!
   */
  private final LinkedHashSet<AggregationOp> aggOps;
  /** Does this aggregator need to compute the count? */
  private final boolean needsCount;
  /** Does this aggregator need to compute the min? */
  private final boolean needsMin;
  /** Does this aggregator need to compute the max? */
  private final boolean needsMax;
  /** Does this aggregator need to compute tuple-level stats? */
  private final boolean needsStats;

  /**
   * Count, always of long type.
   */
  private long count;

  /**
   * min and max keeps the same data type as the aggregating column.
   */
  private String min, max;

  /**
   * Result schema. It's automatically generated according to the {@link StringAggregator#aggOps}.
   */
  private final Schema resultSchema;

  /**
   * Aggregate operations applicable for string columns.
   */
  public static final Set<AggregationOp> AVAILABLE_AGG = ImmutableSet.of(AggregationOp.COUNT, AggregationOp.MAX,
      AggregationOp.MIN);

  /**
   * @param aFieldName aggregate field name for use in output schema.
   * @param aggOps the aggregate operation to simultaneously compute.
   */
  public StringAggregator(final String aFieldName, final AggregationOp[] aggOps) {
    Objects.requireNonNull(aFieldName, "aFieldName");
    if (aggOps.length == 0) {
      throw new IllegalArgumentException("No aggregation operations are selected");
    }

    this.aggOps = new LinkedHashSet<>(Arrays.asList(aggOps));
    if (!AVAILABLE_AGG.containsAll(this.aggOps)) {
      throw new IllegalArgumentException("Unsupported aggregation(s) on int column: "
          + Sets.difference(this.aggOps, AVAILABLE_AGG));
    }

    needsCount = AggUtils.needsCount(this.aggOps);
    needsMin = AggUtils.needsMin(this.aggOps);
    needsMax = AggUtils.needsMax(this.aggOps);
    needsStats = AggUtils.needsStats(this.aggOps);

    final ImmutableList.Builder<Type> types = ImmutableList.builder();
    final ImmutableList.Builder<String> names = ImmutableList.builder();
    for (AggregationOp op : this.aggOps) {
      switch (op) {
        case COUNT:
          types.add(Type.LONG_TYPE);
          names.add("count_" + aFieldName);
          break;
        case MAX:
          types.add(Type.STRING_TYPE);
          names.add("max_" + aFieldName);
          break;
        case MIN:
          types.add(Type.STRING_TYPE);
          names.add("min_" + aFieldName);
          break;
        case AVG:
        case STDEV:
        case SUM:
          throw new UnsupportedOperationException("Aggregate " + op + " on type String");
      }
    }
    resultSchema = new Schema(types, names);
  }

  @Override
  public void add(final ReadableColumn from) {
    Objects.requireNonNull(from, "from");
    final int numTuples = from.size();
    if (numTuples == 0) {
      return;
    }
    if (needsCount) {
      count = LongMath.checkedAdd(count, numTuples);
    }
    if (needsStats) {
      for (int i = 0; i < from.size(); ++i) {
        addStringStats(from.getString(i));
      }
    }
  }

  @Override
  public void add(final ReadableTable from, final int fromColumn) {
    Objects.requireNonNull(from, "from");
    add(from.asColumn(fromColumn));
  }

  @Override
  public void add(final ReadableTable table, final int column, final int row) {
    Objects.requireNonNull(table, "table");
    addString(table.getString(column, row));
  }

  /**
   * Add the specified value to this aggregator.
   * 
   * @param value the value to be added
   */
  public void addString(final String value) {
    Objects.requireNonNull(value, "value");
    if (needsCount) {
      count = LongMath.checkedAdd(count, 1);
    }
    if (needsStats) {
      addStringStats(value);
    }
  }

  /**
   * Helper function to add value to this aggregator. Note this does NOT update count.
   * 
   * @param value the value to be added
   */
  private void addStringStats(final String value) {
    Objects.requireNonNull(value, "value");
    if (needsMin) {
      if ((min == null) || (min.compareTo(value) > 0)) {
        min = value;
      }
    }
    if (needsMax) {
      if (max == null || max.compareTo(value) < 0) {
        max = value;
      }
    }
  }

  @Override
  public void getResult(final AppendableTable dest, final int destColumn) {
    int idx = destColumn;
    for (AggregationOp op : aggOps) {
      switch (op) {
        case COUNT:
          dest.putLong(idx, count);
          break;
        case MAX:
          dest.putString(idx, max);
          break;
        case MIN:
          dest.putString(idx, min);
          break;
        case AVG:
        case STDEV:
        case SUM:
          throw new UnsupportedOperationException("Aggregate " + op + " on type String");
      }
    }
  }

  @Override
  public Schema getResultSchema() {
    return resultSchema;
  }

  @Override
  public Type getType() {
    return Type.STRING_TYPE;
  }
}