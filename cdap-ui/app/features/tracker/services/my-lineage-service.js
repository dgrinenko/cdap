/*
 * Copyright © 2016 Cask Data, Inc.
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

class myLineageService {

  /**
   *  Takes in the response from backend, and returns an object with list of
   *  nodes and connections.
   **/
  parseLineageResponse(response, params) {
    let currentActiveNode = [
      params.entityType === 'datasets' ? 'dataset' : 'stream',
      params.namespace,
      params.entityId
    ].join('.');

    let connections = [];
    let uniqueNodes = {};
    let nodes = [];

    /* SETTING NODES */
    angular.forEach(response.programs, (value, key) => {
      let nodeObj = {
        label: value.entityId.id.id,
        id: key,
        nodeType: 'program',
        applicationId: value.entityId.id.application.applicationId,
        entityId: value.entityId.id.id,
        entityType: this.parseProgramType(value.entityId.id.type),
        displayType: value.entityId.id.type,
        icon: this.getProgramIcon(value.entityId.id.type),
        runs: []
      };

      uniqueNodes[key] = nodeObj;
    });

    angular.forEach(response.data, (value, key) => {
      let data = this.parseDataInfo(value);

      let nodeObj = {
        label: data.name,
        id: key,
        nodeType: 'data',
        entityId: data.name,
        entityType: data.type,
        displayType: data.displayType,
        icon: data.icon
      };

      uniqueNodes[key] = nodeObj;
    });


    /* SETTING CONNECTIONS */
    angular.forEach(response.relations, (rel) => {
      let isUnknownOrBoth = rel.access === 'both' || rel.access === 'unknown';

      if (rel.access === 'read' || isUnknownOrBoth) {
        let dataId = rel.data === currentActiveNode ? rel.data : rel.data + '-read';
        let programId = rel.data === currentActiveNode ? rel.program + '-read' : rel.program + '-write';
        connections.push({
          source: dataId,
          target: programId,
          type: 'read'
        });

        nodes.push({
          dataId: dataId,
          uniqueNodeId: rel.data
        });
        nodes.push({
          dataId: programId,
          uniqueNodeId: rel.program
        });
      }

      if (rel.access === 'write' || isUnknownOrBoth) {
        let dataId = rel.data === currentActiveNode ? rel.data : rel.data + '-write';
        let programId = rel.data === currentActiveNode ? rel.program + '-write' : rel.program + '-read';
        connections.push({
          source: programId,
          target: dataId,
          type: 'write'
        });

        nodes.push({
          dataId: dataId,
          uniqueNodeId: rel.data
        });
        nodes.push({
          dataId: programId,
          uniqueNodeId: rel.program
        });
      }

      uniqueNodes[rel.program].runs = uniqueNodes[rel.program].runs.concat(rel.runs);
    });

    nodes = _.uniq(nodes, (n) => { return n.dataId; });
    let graph = this.getGraphLayout(nodes, connections);
    this.mapNodesLocation(nodes, graph);

    return {
      connections: connections,
      nodes: nodes,
      uniqueNodes: uniqueNodes,
      graph: graph
    };
  }

  parseProgramType(programType) {
    let program = '';
    switch (programType) {
      case 'Flow':
        program = 'flows';
        break;
      case 'Mapreduce':
        program = 'mapreduce';
        break;
      case 'Spark':
        program = 'spark';
        break;
      case 'Worker':
        program = 'workers';
        break;
      case 'Workflow':
        program = 'workflows';
        break;
      case 'Service':
        program = 'services';
        break;
    }

    return program;
  }

  parseDataInfo(data) {
    let obj = {};
    if (data.entityId.type === 'datasetinstance') {
      obj = {
        name: data.entityId.id.instanceId,
        type: 'datasets',
        icon: 'icon-datasets',
        displayType: 'Dataset'
      };
    } else {
      obj = {
        name: data.entityId.id.streamName,
        type: 'streams',
        icon: 'icon-streams',
        displayType: 'Stream'
      };
    }

    return obj;
  }

  getProgramIcon(programType) {
    let iconMap = {
      'Flow': 'icon-tigon',
      'Mapreduce': 'icon-mapreduce',
      'Spark': 'icon-spark',
      'Worker': 'icon-worker',
      'Workflow': 'icon-workflow',
      'Service': 'icon-service'
    };

    return iconMap[programType];
  }

  getGraphLayout(nodes, connections) {
    var graph = new dagre.graphlib.Graph();
    graph.setGraph({
      nodesep: 90,
      ranksep: 100,
      rankdir: 'LR',
      marginx: 90,
      marginy: 25
    });
    graph.setDefaultEdgeLabel(function() { return {}; });

    angular.forEach(nodes, (node) => {
      var id = node.dataId;
      graph.setNode(id, { width: 175, height: 55 });
    });

    angular.forEach(connections, (connection) => {
      graph.setEdge(connection.source, connection.target);
    });

    dagre.layout(graph);

    return graph;
  }

  mapNodesLocation(nodes, graph) {
    angular.forEach(nodes, (node) => {
      node._uiLocation = {
        top: graph._nodes[node.dataId].y + 'px',
        left: graph._nodes[node.dataId].x + 'px'
      };
    });
  }
}

myLineageService.$inject = [];

angular.module(PKG.name + '.feature.tracker')
  .service('myLineageService', myLineageService);
