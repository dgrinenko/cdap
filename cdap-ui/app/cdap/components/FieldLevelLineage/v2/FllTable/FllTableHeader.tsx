/*
 * Copyright © 2020 Cask Data, Inc.
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

import React, { useState, useContext } from 'react';
import withStyles, { WithStyles, StyleRules } from '@material-ui/core/styles/withStyles';
import classnames from 'classnames';
import { IField } from 'components/FieldLevelLineage/v2/Context/FllContextHelper';
import T from 'i18n-react';
import If from 'components/If';

const styles = (theme): StyleRules => {
  return {
    tableHeader: {
      borderBottom: `2px solid ${theme.palette.grey[300]}`,
      height: '40px',
      paddingLeft: '10px',
      fontWeight: 'bold',
      fontSize: '1rem',
      overflow: 'hidden',
      ' & .table-name': {
        overflow: 'hidden',
        whiteSpace: 'nowrap',
        textOverflow: 'ellipsis',
      },
    },
    tableSubheader: {
      color: theme.palette.grey[100],
      fontWeight: 'normal',
    },
  };
};

interface ITableHeaderProps extends WithStyles<typeof styles> {
  fields: IField[];
  isTarget: boolean;
  isExpanded: boolean;
}

function FllTableHeader({ fields, isTarget, isExpanded = false, classes }: ITableHeaderProps) {
  const count: number = fields.length;
  const tableName = fields[0].dataset;
  const options = { context: count };
  return (
    <div className={classes.tableHeader}>
      <div className="table-name">{tableName}</div>
      <div className={classes.tableSubheader}>
        {isTarget || isExpanded
          ? T.translate('features.FieldLevelLineage.v2.FllTable.fieldsCount', options)
          : T.translate('features.FieldLevelLineage.v2.FllTable.relatedFieldsCount', options)}
      </div>
    </div>
  );
}

const StyledTableHeader = withStyles(styles)(FllTableHeader);

export default StyledTableHeader;
