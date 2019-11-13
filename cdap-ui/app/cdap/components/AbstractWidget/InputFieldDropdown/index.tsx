/*
 * Copyright © 2019 Cask Data, Inc.
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
import React from 'react';
import Select from 'components/AbstractWidget/FormInputs/Select';
import { IWidgetProps, IStageSchema } from 'components/AbstractWidget';
import { objectQuery } from 'services/helpers';
import { WIDGET_PROPTYPES } from 'components/AbstractWidget/constants';
import MultiSelect from '../FormInputs/MultiSelect';

interface IField {
  name: string;
  type: string;
}

const delimiter: string = ',';

interface IInputFieldProps extends IWidgetProps<object> {}

// We are assuming all incoming stages have the same schema
function getFields(schemas: IStageSchema[], allowedTypes: string[]) {
  let fields = [];
  if (!schemas || schemas.length === 0) {
    return fields;
  }
  const stage = schemas[0];

  try {
    const unparsedFields = JSON.parse(stage.schema).fields;

    if (unparsedFields.length > 0) {
      fields = unparsedFields
        .filter((field: IField) => allowedTypes.length == 0 || allowedTypes.includes(field.type))
        .map((field: IField) => field.name);
    }
  } catch {
    // tslint:disable-next-line: no-console
    console.log('Error: Invalid JSON schema');
  }
  return fields;
}

const InputFieldDropdown: React.FC<IInputFieldProps> = ({
  value,
  onChange,
  disabled,
  extraConfig,
  widgetProps,
}) => {
  const inputSchema = objectQuery(extraConfig, 'inputSchema');

  const isMultiSelect: boolean = objectQuery(widgetProps, 'multiselect') || false;
  const allowedTypes: string[] = objectQuery(widgetProps, 'allowed-types') || [];

  const fieldValues = getFields(inputSchema, allowedTypes);

  value = value
    .toString()
    .split(delimiter)
    .filter((value) => fieldValues.includes(value))
    .toString();

  if (isMultiSelect) {
    const multiSelectWidgetProps = {
      delimiter,
      options: fieldValues.map((field) => ({ id: field, label: field })),
    };

    return (
      <MultiSelect
        value={value}
        onChange={onChange}
        widgetProps={multiSelectWidgetProps}
        disabled={disabled}
      />
    );
  } else {
    const slectWidgetProps = {
      options: fieldValues,
    };
    return (
      <Select
        value={value}
        onChange={onChange}
        widgetProps={slectWidgetProps}
        disabled={disabled}
      />
    );
  }
};

export default InputFieldDropdown;

(InputFieldDropdown as any).propTypes = WIDGET_PROPTYPES;
