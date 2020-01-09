/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import { loginIfRequired, getGenericEndpoint, getArtifactsPoll } from '../helpers';
import { DEFAULT_GCP_PROJECTID, DEFAULT_GCP_SERVICEACCOUNT_PATH } from '../support/constants';
import { INodeInfo, INodeIdentifier } from '../typings';

let headers = {};

const TEST_BQ_DATASET_PROJECT = 'datasetproject';
const TEST_DATASET = 'joiner_test';
const TABLE1 = 'test1';
const TABLE2 = 'test2';
const TABLE1_FIELDS = ['field1', 'field2', 'field3'];
const TABLE2_FIELDS = ['field1', 'field4'];

describe('Creating pipeline with joiner in pipeline studio', () => {
  before(() => {
    loginIfRequired().then(() => {
      cy.getCookie('CDAP_Auth_Token').then((cookie) => {
        if (!cookie) {
          return;
        }
        headers = {
          Authorization: 'Bearer ' + cookie.value,
        };
      });
    });
  });

  beforeEach(() => {
    getArtifactsPoll(headers);
  });

  it('Should be able to build a complex pipeline with joiner widget', () => {
    cy.visit('/pipelines/ns/default/studio');
    const TEST_PIPELINE_NAME = 'joiner_pipeline_name';

    // Create two big query sources
    const sourceNode1: INodeInfo = { nodeName: 'BigQueryTable', nodeType: 'batchsource' };
    const sourceNodeId1: INodeIdentifier = { ...sourceNode1, nodeId: '0' };
    const source1Properties = {
      referenceName: 'BQ_Source1',
      project: DEFAULT_GCP_PROJECTID,
      datasetProject: TEST_BQ_DATASET_PROJECT,
      dataset: TEST_DATASET,
      table: TABLE1,
      serviceFilePath: DEFAULT_GCP_SERVICEACCOUNT_PATH,
    };

    const sourceNode2: INodeInfo = { nodeName: 'BigQueryTable', nodeType: 'batchsource' };
    const sourceNodeId2: INodeIdentifier = { ...sourceNode2, nodeId: '1' };
    const source2Properties = {
      referenceName: 'BQ_Source2',
      project: DEFAULT_GCP_PROJECTID,
      datasetProject: TEST_BQ_DATASET_PROJECT,
      dataset: TEST_DATASET,
      table: TABLE2,
      serviceFilePath: DEFAULT_GCP_SERVICEACCOUNT_PATH,
    };

    // Create joiner
    const joinerNode: INodeInfo = { nodeName: 'Joiner', nodeType: 'batchjoiner' };
    const joinerNodeId: INodeIdentifier = { ...joinerNode, nodeId: '2' };

    // Create a sink node
    const sinkNode: INodeInfo = { nodeName: 'BigQueryTable', nodeType: 'batchsink' };
    const sinkNodeId: INodeIdentifier = { ...sinkNode, nodeId: '3' };

    // Add all nodes and connect them

    cy.add_node_to_canvas(sourceNode1);
    cy.add_node_to_canvas(sourceNode2);

    cy.open_analytics_panel();
    cy.add_node_to_canvas(joinerNode);

    cy.open_sink_panel();
    cy.add_node_to_canvas(sinkNode);

    cy.get('[data-cy="pipeline-clean-up-graph-control"]').click();
    cy.get('[data-cy="pipeline-fit-to-screen-control"]').click();

    cy.connect_two_nodes(sourceNodeId1, joinerNodeId, getGenericEndpoint);
    cy.connect_two_nodes(sourceNodeId2, joinerNodeId, getGenericEndpoint);
    cy.connect_two_nodes(joinerNodeId, sinkNodeId, getGenericEndpoint);

    cy.get('[data-cy="pipeline-clean-up-graph-control"]').click();
    cy.get('[data-cy="pipeline-fit-to-screen-control"]').click();

    // configure the plugin properties for BigQuery source 1
    cy.get('[data-cy="plugin-node-BigQueryTable-batchsource-0"] .node .node-configure-btn')
      .invoke('show')
      .click();

    cy.get('input[data-cy="referenceName"]').type(source1Properties.referenceName);
    cy.get('input[data-cy="project"]')
      .clear()
      .type(source1Properties.project);
    cy.get('input[data-cy="dataset"]').type(source1Properties.dataset);
    cy.get('input[data-cy="table"]').type(source1Properties.table);
    cy.get('input[data-cy="serviceFilePath"]')
      .clear()
      .type(source1Properties.serviceFilePath);

    // Validate and check fields for source1
    cy.get('[data-cy="plugin-properties-validate-btn"]').click();
    cy.get('[data-cy="plugin-validation-success-msg"]', { timeout: 4000 }).should('exist');
    TABLE1_FIELDS.forEach((field) => {
      cy.get(`[data-cy="${field}-schema-field"]`).should('exist');
    });

    cy.get('[data-testid="close-config-popover"]').click();

    // configure the plugin properties for BigQuery source 1
    cy.get('[data-cy="plugin-node-BigQueryTable-batchsource-1"] .node .node-configure-btn')
      .invoke('show')
      .click();

    cy.get('input[data-cy="referenceName"]').type(source2Properties.referenceName);
    cy.get('input[data-cy="project"]')
      .clear()
      .type(source2Properties.project);
    cy.get('input[data-cy="dataset"]').type(source2Properties.dataset);
    cy.get('input[data-cy="table"]').type(source2Properties.table);
    cy.get('input[data-cy="serviceFilePath"]')
      .clear()
      .type(source2Properties.serviceFilePath);

    // Use get Schema button to check fields for source2
    cy.get('[data-cy="get-schema-btn"').click();
    cy.get('[data-cy="get-schema-btn"', { timeout: 3000 }).contains('GET SCHEMA');

    TABLE2_FIELDS.forEach((field) => {
      cy.get(`[data-cy="${field}-schema-field"]`).should('exist');
    });

    cy.get('[data-testid="close-config-popover"]').click();

    // check if schemas propagated correctly to the joiner widget
    cy.get('[data-cy="plugin-node-Joiner-batchjoiner-1"] .node .node-configure-btn')
      .invoke('show')
      .click();

    // Check for both inputs

    // Check the fields for each input

    // Check the output schema

    // set name and description for pipeline

    // Export the pipeline to joiner is configured correctly

    // Deploy pipeline

    // Check if pipeline deployed
  });
});
