/*
 * Copyright © 2018-2019 Cask Data, Inc.
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

package io.cdap.cdap.data2.metadata.lineage.field;

import com.google.common.base.Charsets;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.cdap.cdap.api.lineage.field.EndPoint;
import io.cdap.cdap.api.lineage.field.InputField;
import io.cdap.cdap.api.lineage.field.Operation;
import io.cdap.cdap.api.lineage.field.OperationType;
import io.cdap.cdap.api.lineage.field.ReadOperation;
import io.cdap.cdap.api.lineage.field.TransformOperation;
import io.cdap.cdap.api.lineage.field.WriteOperation;
import io.cdap.cdap.common.utils.Checksums;
import io.cdap.cdap.proto.codec.OperationTypeAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class representing the information about field lineage for a single program run.
 * Currently we store the operations associated with the field lineage and corresponding
 * checksum. Algorithm to compute checksum is same as how Avro computes the Schema fingerprint.
 * (https://issues.apache.org/jira/browse/AVRO-1006). The implementation of fingerprint
 * algorithm is taken from {@code org.apache.avro.SchemaNormalization} class. Since the checksum
 * is persisted in store, any change to the canonicalize form or fingerprint algorithm would
 * require upgrade step to update the stored checksums.
 */
public class FieldLineageInfo {
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Operation.class, new OperationTypeAdapter())
    .create();
  private static final Logger LOG = LoggerFactory.getLogger(FieldLineageInfo.class);

  private final Set<Operation> operations;

  // Map of EndPoint representing destination to the set of fields belonging to it.
  private Map<EndPoint, Set<String>> destinationFields;

  // For each (EndPoint,Field) for the destination we maintain the incoming summary i.e.
  // combination of (EndPoint,Field) which were responsible for generating it.
  private Map<EndPointField, Set<EndPointField>> incomingSummary;

  // For each (EndPoint,Field) combination from the source, we maintain the outgoing summary i.e.
  // set of (EndPoint, Field)s which were generated by it.
  private Map<EndPointField, Set<EndPointField>> outgoingSummary;

  // We maintain the set of fields dropped in this lineage; ones with incoming, but no outgoing.
  private Set<String> droppedFields;

  private transient Set<WriteOperation> writeOperations;

  private transient Set<ReadOperation> readOperations;

  // Map of operation name to the operation
  private transient Map<String, Operation> operationsMap;

  // Source endpoints in the lineage info
  private transient Set<EndPoint> sources;

  // Destination endpoints in the lineage info
  private transient Set<EndPoint> destinations;

  // outgoing operation map. stores the operation name as key and set of operations which uses it as input
  private transient Map<String, Set<Operation>> operationOutgoingConnections;

  private long checksum;

  /**
   * Create an instance of a class from supplied collection of operations.
   * Validations are performed on the collection before creating instance. All of the operations
   * must have unique names. Collection must have at least one operation of type READ and one
   * operation of type WRITE. The origins specified for the {@link InputField} are also validated
   * to make sure the operation with the corresponding name exists in the collection. However, we do not
   * validate the existence of path to the fields in the destination from sources. If no such path exists
   * then the lineage will be incomplete.
   *
   * Apart from collection of operations, this instance also stores the fields belonging
   * to the destination along with the incoming and outgoing summaries. Computing summaries
   * can be expensive operation, so it is better to do it while emitting the instance to TMS
   * from program container, so that its subscriber simply insert it into the dataset without any
   * possibility to running into the dataset transaction timeouts. For this reason, create instance
   * using this constructor from program container, before writing to TMS.
   *
   * @param operations the collection of field lineage operations
   * @throws IllegalArgumentException if validation fails
   */
  public FieldLineageInfo(Collection<? extends Operation> operations) {
    this(operations, true);
  }

  /**
   * When computation of summaries is not required, this constructor can be used to create an instance.
   * Specifically while serving REST api for getting operations on a field, we create instance with the
   * operations stored in dataset. However we do not need to compute summaries at that point, so provide
   * computeSummaries as {@code false}.
   *
   * @param operations the collection of field lineage operations
   * @param computeSummaries boolean flag to determine whether summaries should be computed
   * @throws IllegalArgumentException if validation fails
   */
  public FieldLineageInfo(Collection<? extends Operation> operations, boolean computeSummaries) {
    LOG.trace("Received field lineage operations {}", GSON.toJson(operations));
    this.operations = new HashSet<>(operations);
    this.droppedFields = new HashSet<>();
    computeAndValidateFieldLineageInfo(operations);
    this.checksum = computeChecksum();
    if (computeSummaries) {
      this.destinationFields = computeDestinationFields();
      this.incomingSummary = computeIncomingSummary();
      this.outgoingSummary = computeOutgoingSummary();
    }
  }

  private void computeAndValidateFieldLineageInfo(Collection<? extends Operation> operations) {
    Set<String> allOrigins = new HashSet<>();

    this.operationsMap = new HashMap<>();
    this.writeOperations = new HashSet<>();
    this.readOperations = new HashSet<>();
    this.operationOutgoingConnections = new HashMap<>();

    for (Operation operation : operations) {
      if (operationsMap.containsKey(operation.getName())) {
        throw new IllegalArgumentException(String.format("All operations provided for creating field " +
                "level lineage info must have unique names. " +
                "Operation name '%s' is repeated.", operation.getName()));

      }

      operationsMap.put(operation.getName(), operation);

      switch (operation.getType()) {
        case READ:
          ReadOperation read = (ReadOperation) operation;
          EndPoint source = read.getSource();
          if (source == null) {
            throw new IllegalArgumentException(String.format("Source endpoint cannot be null for the read " +
                    "operation '%s'.", read.getName()));
          }
          readOperations.add(read);
          break;
        case TRANSFORM:
          TransformOperation transform = (TransformOperation) operation;
          Set<String> origins = transform.getInputs().stream().map(InputField::getOrigin).collect(Collectors.toSet());
          // for each origin corresponding to the input fields there is a connection from that origin to this operation
          for (String origin : origins) {
            Set<Operation> connections = operationOutgoingConnections.computeIfAbsent(origin, k -> new HashSet<>());
            connections.add(transform);
          }
          allOrigins.addAll(origins);
          if (transform.getInputs().size() > transform.getOutputs().size()) {
            droppedFields.addAll(Sets.difference(transform.getInputs().stream().map(InputField::getName)
                .collect(Collectors.toSet()), new HashSet<>(transform.getOutputs())));
          }
          break;
        case WRITE:
          WriteOperation write = (WriteOperation) operation;
          EndPoint destination = write.getDestination();
          if (destination == null) {
            throw new IllegalArgumentException(String.format("Destination endpoint cannot be null for the write " +
                    "operation '%s'.", write.getName()));
          }

          origins = write.getInputs().stream().map(InputField::getOrigin).collect(Collectors.toSet());
          // for each origin corresponding to the input fields there is a connection from that origin to this operation
          for (String origin : origins) {
            Set<Operation> connections = operationOutgoingConnections.computeIfAbsent(origin, k -> new HashSet<>());
            connections.add(write);
          }
          allOrigins.addAll(origins);
          writeOperations.add(write);
          break;
        default:
          // no-op
      }
    }

    Set<String> operationsWithNoOutgoingConnections
            = Sets.difference(operationsMap.keySet(), operationOutgoingConnections.keySet());
    // put empty set for operations with no outgoing connection rather than checking for null later
    for (String operation : operationsWithNoOutgoingConnections) {
      operationOutgoingConnections.put(operation, new HashSet<>());
    }

    if (readOperations.isEmpty()) {
      throw new IllegalArgumentException("Field level lineage requires at least one operation of type 'READ'.");
    }

    if (writeOperations.isEmpty()) {
      throw new IllegalArgumentException("Field level lineage requires at least one operation of type 'WRITE'.");
    }

    Sets.SetView<String> invalidOrigins = Sets.difference(allOrigins, operationsMap.keySet());
    if (!invalidOrigins.isEmpty()) {
      throw new IllegalArgumentException(String.format("No operation is associated with the origins '%s'.",
              invalidOrigins));
    }
  }

  /**
   * @return the checksum for the operations
   */
  public long getChecksum() {
    return checksum;
  }

  /**
   * @return the operations
   */
  public Set<Operation> getOperations() {
    return operations;
  }

  /**
   * @return the map of destination EndPoint's and corresponding fields those were written to them
   */
  public Map<EndPoint, Set<String>> getDestinationFields() {
    if (destinationFields == null) {
      destinationFields = computeDestinationFields();
    }
    return destinationFields;
  }

  public Map<EndPointField, Set<EndPointField>> getIncomingSummary() {
    if (incomingSummary == null) {
      incomingSummary = computeIncomingSummary();
    }
    return incomingSummary;
  }

  public Map<EndPointField, Set<EndPointField>> getOutgoingSummary() {
    if (outgoingSummary == null) {
      outgoingSummary = computeOutgoingSummary();
    }
    return outgoingSummary;
  }

  /**
   * @return all {@link EndPoint}s representing the source for read operations
   */
  public Set<EndPoint> getSources() {
    if (sources == null || sources.size() == 0) {
      populateSourcesAndDestinations();
    }
    return sources;
  }

  /**
   * @return all {@link EndPoint}s representing the destination for write operations
   */
  public Set<EndPoint> getDestinations() {
    if (destinations == null || destinations.size() == 0) {
      populateSourcesAndDestinations();
    }
    return destinations;
  }

  private void populateSourcesAndDestinations() {
    sources = new HashSet<>();
    destinations = new HashSet<>();
    for (Operation operation : operations) {
      if (OperationType.READ == operation.getType()) {
        ReadOperation read = (ReadOperation) operation;
        sources.add(read.getSource());
      } else if (OperationType.WRITE == operation.getType()) {
        WriteOperation write = (WriteOperation) operation;
        destinations.add(write.getDestination());
      }
    }
  }

  private long computeChecksum() {
    return Checksums.fingerprint64(canonicalize().getBytes(Charsets.UTF_8));
  }

  private Map<EndPoint, Set<String>> computeDestinationFields() {
    if (writeOperations == null) {
      computeAndValidateFieldLineageInfo(this.operations);
    }

    Map<EndPoint, Set<String>> destinationFields = new HashMap<>();
    for (WriteOperation write : this.writeOperations) {
      Set<String> endPointFields = destinationFields.computeIfAbsent(write.getDestination(), k -> new HashSet<>());
      for (InputField field : write.getInputs()) {
        endPointFields.add(field.getName());
      }
      endPointFields.addAll(droppedFields);
    }
    return destinationFields;
  }

  private Map<EndPointField, Set<EndPointField>> computeIncomingSummary() {
    if (writeOperations == null) {
      computeAndValidateFieldLineageInfo(this.operations);
    }

    Map<EndPointField, Set<EndPointField>> summary = new HashMap<>();
    for (WriteOperation write : writeOperations) {
      List<InputField> inputs = write.getInputs();
      for (InputField input : inputs) {
        computeIncomingSummaryHelper(new EndPointField(write.getDestination(), input.getName()),
                                     operationsMap.get(input.getOrigin()), write, summary);
      }
    }
    return summary;
  }

  /**
   * Helper method to compute the incoming summary
   *
   * @param field the {@link EndPointField} whose summary needs to be calculated
   * @param currentOperation the operation being processed. Since we are processing incoming this operation is on the
   * left side if graph is imagined in horizontal orientation or this operation is the input to the to
   * previousOperation
   * @param previousOperation the previous operation which is processed and reside on right to the current operation if
   * the graph is imagined to be in horizontal orientation.
   * @param summary a {@link Map} of {@link EndPointField} to {@link Set} of {@link EndPointField} which represents all
   * the fields which have incoming connection the key field
   */
  private void computeIncomingSummaryHelper(EndPointField field, Operation currentOperation,
                                            Operation previousOperation,
                                            Map<EndPointField, Set<EndPointField>> summary) {
    if (currentOperation.getType() == OperationType.READ) {
      // if current operation is of type READ, previous operation must be of type TRANSFORM or WRITE
      // get only the input fields from the previous operations for which the origin is current READ operation
      Set<InputField> inputFields = new HashSet<>();
      if (OperationType.WRITE == previousOperation.getType()) {
        WriteOperation previousWrite = (WriteOperation) previousOperation;
        inputFields = new HashSet<>(previousWrite.getInputs());
      } else if (OperationType.TRANSFORM == previousOperation.getType()) {
        TransformOperation previousTransform = (TransformOperation) previousOperation;
        inputFields = new HashSet<>(previousTransform.getInputs());
      }
      Set<EndPointField> sourceEndPointFields = summary.computeIfAbsent(field, k -> new HashSet<>());

      // for all the input fields of the previous operation if the origin was current operation (remember we are
      // traversing backward)
      ReadOperation read = (ReadOperation) currentOperation;
      EndPoint source = read.getSource();
      for (InputField inputField : inputFields) {
        if (inputField.getOrigin().equals(currentOperation.getName())) {
          sourceEndPointFields.add(new EndPointField(source, inputField.getName()));
        }
      }
      // reached the end of graph unwind the recursive calls
      return;
    }

    // for transform we traverse backward in graph further through the inputs of the transform
    if (currentOperation.getType() == OperationType.TRANSFORM) {
      TransformOperation transform = (TransformOperation) currentOperation;
      // optimization to avoid repeating work if there are input fields with the same origin
      Set<String> transformOrigins = transform.getInputs().stream()
        .map(InputField::getOrigin)
        .collect(Collectors.toSet());
      for (String transformOrigin : transformOrigins) {
        computeIncomingSummaryHelper(field, operationsMap.get(transformOrigin), currentOperation, summary);
      }
    }
  }

  private Map<EndPointField, Set<EndPointField>> computeOutgoingSummary() {
    if (incomingSummary == null) {
      incomingSummary = computeIncomingSummary();
    }

    Map<EndPointField, Set<EndPointField>> outgoingSummary = new HashMap<>();
    for (Map.Entry<EndPointField, Set<EndPointField>> entry : incomingSummary.entrySet()) {
      Set<EndPointField> values = entry.getValue();
      for (EndPointField value : values) {
        Set<EndPointField> outgoingEndPointFields = outgoingSummary.computeIfAbsent(value, k -> new HashSet<>());
        outgoingEndPointFields.add(entry.getKey());
      }
    }
    for (String field : droppedFields) {
      EndPointField endPointField = new EndPointField((EndPoint) getSources().toArray()[0], field);
      outgoingSummary.put(endPointField, new HashSet<>());
    }
    return outgoingSummary;
  }

  /**
   * <p>Get the subset of operations that were responsible for computing the specified field of
   * a specified destination.</p>
   * <p>For example if the operation are as follow</p>
   * <pre>
   * pRead: personFile -> (offset, body)
   * parse: body -> (id, name, address)
   * cRead: codeFile -> id
   * codeGen: (parse.id, cRead.id) -> id
   * sWrite: (codeGen.id, parse.name, parse.address) -> secureStore
   * iWrite: (parse.id, parse.name, parse.address) -> insecureStore
   * </pre>
   * <p>If the destination field is 'id' field of insecureStore then the result set will contain the operations iWrite,
   * parse, pRead.</p>
   * <p>If the destination field is 'id' field of secureStore then the result set will contain the operations sWrite,
   * codeGen, parse, pRead, cRead.</p>
   *
   * @param destinationField the EndPointField for which the operations need to find out
   * @return the subset of operations
   */
  Set<Operation> getIncomingOperationsForField(EndPointField destinationField) {
    if (writeOperations == null) {
      computeAndValidateFieldLineageInfo(this.operations);
    }

    Set<Operation> visitedOperations = new HashSet<>();
    for (WriteOperation write : writeOperations) {
      // if the write operation destination was not the dataset to which the destinationField belongs to
      if (!write.getDestination().equals(destinationField.getEndPoint())) {
        continue;
      }

      Set<InputField> filteredInputs =
        write.getInputs().stream().filter(input -> input.getName().equals(destinationField.getField()))
          .collect(Collectors.toSet());

      for (InputField input : filteredInputs) {
        // mark this write operation as visited
        visitedOperations.add(write);
        // traverse backward in the graph by looking up the origin of this input field which is the operation
        // which computed this destinationField
        getIncomingOperationsForFieldHelper(operationsMap.get(input.getOrigin()), visitedOperations);
      }
    }
    return visitedOperations;
  }

  /**
   * Recursively traverse the graph to calculate the incoming operation.
   *
   * @param currentOperation the current operation from which the graph needs to explored
   * @param visitedOperations all the operations visited so far
   */
  private void getIncomingOperationsForFieldHelper(Operation currentOperation, Set<Operation> visitedOperations) {
    if (!visitedOperations.add(currentOperation)) {
      return;
    }

    // reached the end of backward traversal
    if (currentOperation.getType() == OperationType.READ) {
      return;
    }

    // for transform we traverse backward in graph further through the inputs of the transform
    if (currentOperation.getType() == OperationType.TRANSFORM) {
      TransformOperation transform = (TransformOperation) currentOperation;
      for (InputField field : transform.getInputs()) {
        getIncomingOperationsForFieldHelper(operationsMap.get(field.getOrigin()), visitedOperations);
      }
    }
  }

  /**
   * <p>Get the subset of operations that used the specified field</p>
   *
   * <p>For example if the operation are as follow</p>
   * <pre>
   * pRead: personFile -> (offset, body)
   * parse: body -> (id, name, address)
   * cRead: codeFile -> id
   * codeGen: (parse.id, cRead.id) -> id
   * sWrite: (codeGen.id, parse.name, parse.address) -> secureStore
   * iWrite: (parse.id, parse.name, parse.address) -> insecureStore
   * </pre>
   *
   * <p>The sourceField is 'id' of codeFile then the returned operations set will contain cRead, codeGen, sWrite.</p>
   * <p>If the sourceField is 'body' of personFile then the returned set of operations will contain pRead, parse,
   * codeGen, sWrite, iWrite.</p>
   *
   * @param sourceField the {@link EndPointField} whose outgoing operations needs to be found
   * @return {@link Set} of {@link Operation} which are outgoing from the given sourceField
   */
  Set<Operation> getOutgoingOperationsForField(EndPointField sourceField) {
    if (readOperations == null) {
      computeAndValidateFieldLineageInfo(this.operations);
    }

    Set<Operation> visitedOperations = new HashSet<>();
    for (ReadOperation readOperation : readOperations) {
      if (!(readOperation.getSource().equals(sourceField.getEndPoint()) &&
        readOperation.getOutputs().contains(sourceField.getField()))) {
        continue;
      }
      // the read operation is for the dataset to which the sourceField belong and it did read the sourceField for
      // which outgoing operation is requested so process it
      visitedOperations.add(readOperation);
      for (Operation outgoingOperation : operationOutgoingConnections.get(readOperation.getName())) {
        // Check that the source field is an input field for the outgoing operation.
        // Consider the example in the method javadoc with:
        //  sourceField = personFile.offset
        //  readOperation = pRead: personFile -> (offset, body)
        //  outgoingOperation = parse: body -> (id, name, address)
        // In this scenario, 'offset' is not an input to the outgoingOperation so we do not need to go further
        // down the graph.
        // If the sourceField was personFile.body, we would need to continue as 'body' is an input to the
        // outgoingOperation.
        InputField inputField = InputField.of(readOperation.getName(), sourceField.getField());
        if (containsInputField(outgoingOperation, inputField)) {
          computeOutgoing(outgoingOperation, visitedOperations);
        }
      }
    }
    return visitedOperations;
  }

  /**
   * Helper method to compute the outgoing connections
   * @param currentOperation current operation which needs to evaluated
   * @param visitedOperations a {@link Set} containing all the operations which has been processed so
   * far.
   */
  private void computeOutgoing(Operation currentOperation, Set<Operation> visitedOperations) {
    // mark this operation if not already done
    if (!visitedOperations.add(currentOperation)) {
      return;
    }

    // base condition: if the current operation is write we have reached the end
    if (currentOperation.getType() == OperationType.WRITE) {
      return;
    }

    // if this is a transform operation then traverse down to all the outgoing operation from this operation
    // expanding further the traversal and exploring the operations
    if (currentOperation.getType() == OperationType.TRANSFORM) {
      TransformOperation transform = (TransformOperation) currentOperation;
      Set<Operation> operations = operationOutgoingConnections.get(transform.getName());
      for (Operation operation : operations) {
        computeOutgoing(operation, visitedOperations);
      }
    }
  }

  /**
   * Checks whether the given field is used in the next operations or not
   *
   * @param nextOperation the next operation which should either be a {@link TransformOperation} or {@link
   * WriteOperation}
   * @param inputField the field whose usage needs to be checked
   * @return true if the field is used in the nextOperation
   */
  private boolean containsInputField(Operation nextOperation, InputField inputField) {
    Set<InputField> inputFields = new HashSet<>();
    if (OperationType.WRITE == nextOperation.getType()) {
      WriteOperation nextWrite = (WriteOperation) nextOperation;
      inputFields = new HashSet<>(nextWrite.getInputs());
    } else if (OperationType.TRANSFORM == nextOperation.getType()) {
      TransformOperation nextTransform = (TransformOperation) nextOperation;
      inputFields = new HashSet<>(nextTransform.getInputs());
    }
    // if the next operation inputFields does contains the given fieldName return true
    return inputFields.contains(inputField);
  }

  /**
   * Sort the operations in topological order. In topological order, each operation in the list
   * is guaranteed to occur before any other operation that reads its outputs.
   *
   * For example, consider following scenario:
   *
   *    read-----------------------write
   *       \                        /
   *       ----parse----normalize---
   *
   * Since write operation is dependent on the read and normalize for its input, it would be
   * last in the order. normalize depends on the parse, so it would appear after parse. Similarly
   * parse operation would appear after the read but before normalize in the returned list.
   *
   * @param operations set of operations to be sorted
   * @return the list containing topologically sorted operations
   */
  public static List<Operation> getTopologicallySortedOperations(Set<Operation> operations) {

    Map<String, Operation> operationMap = new HashMap<>();
    Set<String> readOperations = new HashSet<>();

    for (Operation operation : operations) {
      operationMap.put(operation.getName(), operation);
      if (OperationType.READ == operation.getType()) {
        readOperations.add(operation.getName());
      }
    }

    // Map of operation name to the set of operation names which take the output of the given operation as
    // an input. This map basically represents the adjacency list for operation.
    // For example consider the following scenario:
    //
    // read----------------------write
    //   \                      /
    //    ----parse---normalize
    //
    // The map would contain:
    // read -> [parse, write]
    // parse -> [normalize]
    // normalize -> [write]
    // write -> []
    Map<String, Set<String>> outgoingOperations = new HashMap<>();

    // Map of operation name to the set of operation names outputs of which given operation takes as an input.
    // For example consider the following scenario:
    //
    // read----------------------write
    //   \                      /
    //    ----parse---normalize
    //
    // The map would contain:
    // read -> []
    // parse -> [read]
    // normalize -> [parse]
    // write -> [read, normalize]
    Map<String, Set<String>> incomingOperations = new HashMap<>();

    for (Operation operation : operations) {
      List<InputField> inputFields = new ArrayList<>();
      switch (operation.getType()) {
        case READ:
          // read has no incoming operation
          incomingOperations.put(operation.getName(), new HashSet<>());
          break;
        case TRANSFORM:
          TransformOperation transform = (TransformOperation) operation;
          inputFields.addAll(transform.getInputs());
          break;
        case WRITE:
          WriteOperation write = (WriteOperation) operation;
          inputFields.addAll(write.getInputs());
          // write has no outgoing operation
          outgoingOperations.put(operation.getName(), new HashSet<>());
          break;
      }

      for (InputField inputField : inputFields) {
        // It is possible that the origin for the current input field is not present in the set of
        // operations that this method receives. Reason for this is, the method can be called from
        // handler, in which case we only return the subset of operations.
        //
        // For example: consider following complete set of operations:
        // read----------------------write
        //   \                      /
        //    ----parse---normalize
        //
        // Now if handler receives the request for operations that are responsible for field say "offset"
        // which was written by "write" operation and was read by "read" operation. In this case we only
        // get subset [read, write] which this method receives, however write operation will still have
        // input fields with origin as normalize, which should be ignored for topological sorting.
        if (!operationMap.containsKey(inputField.getOrigin())) {
          continue;
        }
        // Current operation is the outgoing operation for origin represented by the input field.
        Set<String> outgoings = outgoingOperations.computeIfAbsent(inputField.getOrigin(), k -> new HashSet<>());
        outgoings.add(operation.getName());

        // Origin represented by the input field is the incoming operation for the current operation.
        Set<String> incomings = incomingOperations.computeIfAbsent(operation.getName(), k -> new HashSet<>());
        incomings.add(inputField.getOrigin());
      }
    }

    List<Operation> orderedOperations = new ArrayList<>();
    Set<String> operationsWithNoIncomings = new HashSet<>(readOperations);
    while (!operationsWithNoIncomings.isEmpty()) {
      String current = operationsWithNoIncomings.iterator().next();
      operationsWithNoIncomings.remove(current);
      if (operationMap.get(current) != null) {
        orderedOperations.add(operationMap.get(current));
      }

      // it is possible that there are no outgoings for the field, since it is possible some field is not used in the
      // downstream of plugins
      Iterator<String> outgoingsIter = outgoingOperations.getOrDefault(current, Collections.emptySet()).iterator();
      while (outgoingsIter.hasNext()) {
        String next = outgoingsIter.next();
        outgoingsIter.remove();
        incomingOperations.get(next).remove(current);
        if (incomingOperations.get(next).isEmpty()) {
          operationsWithNoIncomings.add(next);
        }
      }
    }

    // check if any cycles
    // remove the entries which has empty outgoing operations now
    outgoingOperations.entrySet().removeIf(next -> next.getValue().isEmpty());

    if (!outgoingOperations.isEmpty()) {
      throw new IllegalArgumentException(String.format("Cycle detected in graph for operations %s",
                                                       outgoingOperations));
    }

    return orderedOperations;
  }

  /**
   * Creates the canonicalize representation of the collection of operations. Canonicalize representation is
   * simply the JSON format of operations. Before creating the JSON, collection of operations is sorted based
   * on the operation name so that irrespective of the order of insertion, same set of operations always generate
   * same canonicalize form. This representation is then used for computing the checksum. So if there are any changes
   * to this representation, upgrade step would be required to update all the checksums stored in store.
   */
  private String canonicalize() {
    List<Operation> ops = new ArrayList<>(operations);
    ops.sort(Comparator.comparing(Operation::getName));
    return GSON.toJson(ops);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FieldLineageInfo)) {
      return false;
    }
    FieldLineageInfo info = (FieldLineageInfo) o;
    return checksum == info.checksum;
  }


  @Override
  public int hashCode() {
    return (int) (checksum ^ (checksum >>> 32));
  }

  public Set<String> getDroppedFields() {
    return droppedFields;
  }
}
