package com.continuuity.data.dataset;

/**
 * This exception is thrown if - for whatever reason - a data set cannot be
 * instantiated at runtime.
 */
public class DataSetInstantiationException extends Exception {

  public DataSetInstantiationException(String msg, Throwable e) {
    super(msg, e);
  }

  public DataSetInstantiationException(String msg) {
    super(msg);
  }
}
