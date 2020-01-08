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

    // Add all nodes

    cy.add_node_to_canvas(sourceNode1);
    cy.add_node_to_canvas(sourceNode2);

    cy.open_analytics_panel();
    cy.add_node_to_canvas(joinerNode);

    cy.open_sink_panel();
    cy.add_node_to_canvas(sinkNode);

    cy.get('[data-cy="pipeline-clean-up-graph-control"]').click();
    cy.get('[data-cy="pipeline-fit-to-screen-control"]').click();

    // connect em
    cy.connect_two_nodes(sourceNodeId1, joinerNodeId, getGenericEndpoint);
    cy.connect_two_nodes(sourceNodeId2, joinerNodeId, getGenericEndpoint);
    cy.connect_two_nodes(joinerNodeId, sinkNodeId, getGenericEndpoint);

    cy.get('[data-cy="pipeline-clean-up-graph-control"]').click();
    cy.get('[data-cy="pipeline-fit-to-screen-control"]').click();

    // configure the plugin properties
    // check if "get schema" button worked - did the correct fields get populated?

    // check if schemas propagated correctly to the joiner widget
    // Fix the alias thing

    // Click on "get schema" and check output schema in Joiner

    // set name and description for pipeline

    // Export the pipeline to validate pipeline is configured correctly
    // Deploy pipeline
    // Check if pipeline deployed
  });
});
