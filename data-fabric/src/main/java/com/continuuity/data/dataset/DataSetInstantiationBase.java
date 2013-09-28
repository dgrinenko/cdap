package com.continuuity.data.dataset;

import com.continuuity.api.data.DataSet;
import com.continuuity.api.data.DataSetSpecification;
import com.continuuity.api.data.dataset.FileDataSet;
import com.continuuity.api.data.dataset.MultiObjectStore;
import com.continuuity.api.data.dataset.ObjectStore;
import com.continuuity.api.data.dataset.table.Table;
import com.continuuity.common.metrics.MetricsCollector;
import com.continuuity.data.DataFabric;
import com.continuuity.data2.RuntimeTable;
import com.continuuity.data2.dataset.api.DataSetClient;
import com.continuuity.data2.transaction.TransactionAware;
import com.continuuity.internal.io.InstantiatorFactory;
import com.continuuity.internal.lang.ClassLoaders;
import com.continuuity.internal.reflect.Fields;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

/**
 * This class implements the core logic of instantiating data set, including injection of the data fabric runtime and
 * built-in data sets.
 */
public class DataSetInstantiationBase {

  private static final Logger LOG = LoggerFactory.getLogger(DataSetInstantiationBase.class);

  // the class loader to use for data set classes
  private final ClassLoader classLoader;
  // the known data set specifications
  private final Map<String, DataSetSpecification> datasets = Maps.newHashMap();

  private final Set<TransactionAware> txAware = Sets.newIdentityHashSet();
  // in this collection we have only datasets initialized with getDataSet() which is OK for now...
  private final Map<TransactionAware, String> txAwareToMetricNames = Maps.newIdentityHashMap();

  private final InstantiatorFactory instantiatorFactory = new InstantiatorFactory(false);

  public DataSetInstantiationBase() {
    this(null);
  }

  public DataSetInstantiationBase(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /**
   * Set the data set spec for all data sets that this instantiator can
   * create. This should be a list of DataSetSpecification's obtained from actual
   * data sets' configure() method.
   * @param specs The list of DataSetSpecification's
   */
  public void setDataSets(Iterable<DataSetSpecification> specs) {
    for (DataSetSpecification spec : specs) {
      this.datasets.put(spec.getName(), spec);
    }
  }

  /**
   * Add one data set spec to this instantiator.
   * @param spec the data set specification
   */
  public void addDataSet(DataSetSpecification spec) {
    this.datasets.put(spec.getName(), spec);
  }

  /**
   * Find out whether the instantiator has a spec for a named data set.
   * @param name the name of the data set
   * @return whether the instantiator knows the spec for the data set
   */
  public boolean hasDataSet(String name) {
    return this.datasets.containsKey(name);
  }

  /**
   *  The main value of this class: Creates a new instance of a data set, as
   *  specified by the matching data set spec, and injects the data fabric
   *  runtime into the new data set.
   *  @param dataSetName the name of the data set to instantiate
   *  @param fabric the data fabric to inject
   */
  public <T extends DataSet> T getDataSet(String dataSetName, DataFabric fabric)
    throws DataSetInstantiationException {

    // find the data set specification
    DataSetSpecification spec = this.datasets.get(dataSetName);
    if (spec == null) {
      throw logAndException(null, "No data set named %s declared for application.", dataSetName);
    }

    // Instantiate the DataSet class, perform DataSet fields injection and invoke initialize(DataSetSpecification).
    String className = spec.getType();
    try {
      return instantiate(spec, fabric, dataSetName);
    } catch (ClassNotFoundException e) {
      throw logAndException(e, "Data set class %s not found", className);

    } catch (IllegalAccessException e) {
      throw logAndException(e, "Unable to access fields or methods in data set class %s", className);

    } catch (NoSuchFieldException e) {
      throw logAndException(e, "Invalid data set field in %s", className);
    }
  }


  /**
   * Returns an immutable life Iterable of {@link TransactionAware} objects.
   */
  // NOTE: this is needed for now to minimize destruction of early integration of txds2
  public Iterable<TransactionAware> getTransactionAware() {
    return Iterables.unmodifiableIterable(txAware);
  }

  public void addTransactionAware(TransactionAware transactionAware) {
    txAware.add(transactionAware);
  }

  public void removeTransactionAware(TransactionAware transactionAware) {
    txAware.remove(transactionAware);
  }

  /**
   * Helper method to cast the created data set object to its correct class.
   * This method is to isolate the unchecked cast (it has to be unchecked
   * because T is a type parameter, we cannot do instanceof or isAssignableFrom
   * on type parameters...) into a small method, that we can annotate with a
   * SuppressWarnings of small scope.
   * @param o The object to be cast
   * @param clz Class of the object.
   * @param <T> The type to cast to
   * @return The cast object of type T
   * @throws DataSetInstantiationException if the cast fails.
   */
  @SuppressWarnings("unchecked")
  private <T extends DataSet> T convert(Object o, Class<?> clz)
    throws DataSetInstantiationException {
    try {
      return (T) o;
    } catch (ClassCastException e) {
      throw logAndException(e, "Incompatible assignment of %s of type %s", DataSet.class.getName(), clz.getName());
    }
  }


  /**
   * Creates actual table implementation supplier if the DataSet is one of those special tables
   * Doing this avoid exposing internal classes into public API.
   */
  private <T extends DataSet> Supplier<T> injectDelegate(DataSet dataSet, DataFabric fabric, String metricName)
                                                            throws NoSuchFieldException, IllegalAccessException {
    Class<?> dsClass = dataSet.getClass();
    final Object delegate;
    final Class<?> delegateClass;

    // Construct corresponding Runtime DataSet. A bit hacky here as it has to list out all known Runtime type.
    if (Table.class.isAssignableFrom(dsClass)) {
      delegate = new RuntimeTable(fabric, metricName);
      delegateClass = Table.class;

    } else if (FileDataSet.class.isAssignableFrom(dsClass)) {
      delegate = new RuntimeFileDataSet(fabric, metricName);
      delegateClass = FileDataSet.class;

    } else if (ObjectStore.class.isAssignableFrom(dsClass)) {
      delegate = RuntimeObjectStore.create(classLoader);
      delegateClass = ObjectStore.class;

    } else if (MultiObjectStore.class.isAssignableFrom(dsClass)) {
      delegate = RuntimeMultiObjectStore.create(classLoader);
      delegateClass = MultiObjectStore.class;

    } else {
      // No Runtime DataSet needs to inject
      return null;
    }

    // Construct the Supplier for injection
    final T instance = convert(delegate, dsClass);
    Supplier<T> supplier = new Supplier<T>() {
      @Override
      public T get() {
        return instance;
      }
    };

    // Find the field to inject to. The fields needs to be of type Supplier<DelegateClass>
    Field delegateField = Fields.findField(TypeToken.of(dsClass), "delegate", new Predicate<Field>() {
      @Override
      public boolean apply(Field field) {
        if (!Supplier.class.equals(field.getType())) {
          return false;
        }
        Type fieldType = field.getGenericType();
        if (!(fieldType instanceof ParameterizedType)) {
          return false;
        }

        Type[] typeArgs = ((ParameterizedType) fieldType).getActualTypeArguments();
        return typeArgs.length == 1 && delegateClass.equals(TypeToken.of(typeArgs[0]).getRawType());
      }
    });
    delegateField.setAccessible(true);
    delegateField.set(dataSet, supplier);

    return supplier;
  }

  /**
   * Instantiate a {@link DataSet} through the {@link DataSetSpecification}.
   * @param spec
   * @param fabric
   * @param metricName
   * @param <T>
   * @return
   * @throws IllegalAccessException
   * @throws ClassNotFoundException
   * @throws DataSetInstantiationException
   * @throws NoSuchFieldException
   */
  private <T extends DataSet> T instantiate(DataSetSpecification spec, DataFabric fabric, String metricName)
                                                       throws IllegalAccessException, ClassNotFoundException,
                                                              DataSetInstantiationException, NoSuchFieldException {

    Class<?> dsClass = ClassLoaders.loadClass(spec.getType(), classLoader, this);
    TypeToken<?> dsType = TypeToken.of(dsClass);
    T instance = convert(instantiatorFactory.get(dsType).create(), dsClass);

    Supplier<T> dataSetDelegate = injectDelegate(instance, fabric, metricName);
    T delegateInstance = dataSetDelegate == null ? null : dataSetDelegate.get();

    // Inject DataSet fields.
    for (Class<?> type : dsType.getTypes().classes().rawTypes()) {
      if (Object.class.equals(type)) {
        break;
      }

      for (Field field : type.getDeclaredFields()) {
        if (!DataSet.class.isAssignableFrom(field.getType())) {
          continue;
        }

        if (!field.isAccessible()) {
          field.setAccessible(true);
        }

        DataSetSpecification fieldDataSetSpec = spec.getSpecificationFor(field.getName());
        if (fieldDataSetSpec == null) {
          throw logAndException(null, "Failed to get DataSetSpecification for %s in class %s.",
                                field.getName(), dsClass.getName());
        }

        DataSet dataSetField = instantiate(fieldDataSetSpec, fabric, metricName);

        // If the current DataSet actually usesdelegate, the DataSet field injection is set on the delegateInstance
        // as that's the one do the actual operation. The fields on the instance is not injected so that
        // any attempt to use them at runtime would result in NPE (which is desired).
        // The reason why the Field object is get from the instance class, but injected to the runtime class works
        // because Runtime class always extends from the instance class.
        field.set(delegateInstance == null ? instance : delegateInstance, dataSetField);
      }
    }

    initialize(instance, spec);

    // Initialize and inject delegate
    if (delegateInstance != null) {
      delegateInstance.initialize(spec);

      // TODO: This is the hack to get ocTable used inside RuntimeTable inside TxAware set.
      if (delegateInstance instanceof RuntimeTable) {
        TransactionAware txAware = ((RuntimeTable) delegateInstance).getTxAware();
        if (txAware != null) {
          this.txAware.add(txAware);
          this.txAwareToMetricNames.put(txAware, metricName);
        }
      }
    }

    return instance;
  }

  private void initialize(DataSet dataSet, DataSetSpecification spec) {
    try {
      dataSet.initialize(spec);
    } catch (Throwable t) {
      throw logAndException(t, "Failed to initialize DataSet %s of name %s", dataSet.getClass(), spec.getName());
    }
  }

  /**
   * Helper method to log a message and create an exception. The caller is
   * responsible for throwing the exception.
   */
  private DataSetInstantiationException logAndException(Throwable e, String message, Object... params)
    throws DataSetInstantiationException {
    String msg;
    DataSetInstantiationException exn;
    if (e == null) {
      msg = String.format("Error instantiating data set: %s.", String.format(message, params));
      exn = new DataSetInstantiationException(msg);
      LOG.error(msg);
    } else {
      msg = String.format("Error instantiating data set: %s. %s", String.format(message, params), e.getMessage());
      exn = new DataSetInstantiationException(msg, e);
      LOG.error(msg, e);
    }
    return exn;
  }

  public void setMetricsCollector(final MetricsCollector programContextMetrics) {

    for (Map.Entry<TransactionAware, String> txAware : this.txAwareToMetricNames.entrySet()) {
      if (txAware.getKey() instanceof DataSetClient) {
        final String dataSetName = txAware.getValue();
        DataSetClient.DataOpsMetrics dataOpsMetrics = new DataSetClient.DataOpsMetrics() {
          @Override
          public void recordRead(int opsCount) {
            if (programContextMetrics != null) {
              programContextMetrics.gauge("store.reads", 1, dataSetName);
              programContextMetrics.gauge("store.ops", 1);
            }
          }

          @Override
          public void recordWrite(int opsCount, int dataSize) {
            if (programContextMetrics != null) {
              programContextMetrics.gauge("store.writes", 1, dataSetName);
              programContextMetrics.gauge("store.bytes", dataSize, dataSetName);
              programContextMetrics.gauge("store.ops", 1);
            }
          }
        };

        ((DataSetClient) txAware.getKey()).setMetricsCollector(dataOpsMetrics);
      }
    }
  }
}
