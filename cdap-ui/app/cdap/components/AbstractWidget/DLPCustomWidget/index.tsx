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

import * as React from 'react';
import DLPRow, { IDropdownOption } from 'components/AbstractWidget/DLPCustomWidget/DLPRow';
import ThemeWrapper from 'components/ThemeWrapper';
import AbstractMultiRowWidget, {
  IMultiRowProps,
} from 'components/AbstractWidget/AbstractMultiRowWidget';
import { objectQuery } from 'services/helpers';
import { WIDGET_PROPTYPES } from 'components/AbstractWidget/constants';
import { IWidgetProperty } from 'components/ConfigurationGroup/types';

interface ITransformProp {
  label: string;
  name: string;
  options: IWidgetProperty[];
}

interface IDLPWidgetProps {
  transforms: ITransformProp[];
  delimiter?: string;
}

interface IDLPProps extends IMultiRowProps<IDLPWidgetProps> {}

class DLPWidgetView extends AbstractMultiRowWidget<IDLPProps> {
  public renderRow = (id, index) => {
    const placeholders = objectQuery(this.props, 'widgetProps', 'placeholders');
    const dropdownOptions = objectQuery(this.props, 'widgetProps', 'dropdownOptions');
    return (
      <DLPRow
        key={id}
        value={this.values[id].value}
        id={id}
        index={index}
        onChange={this.editRow}
        addRow={this.addRow.bind(this, index)}
        removeRow={this.removeRow.bind(this, index)}
        autofocus={this.state.autofocus === id}
        changeFocus={this.changeFocus}
        disabled={this.props.disabled}
        placeholders={placeholders}
        dropdownOptions={dropdownOptions}
        forwardedRef={this.values[id].ref}
        errors={this.props.errors}
      />
    );
  };
}

export default function DLPWidget(props) {
  alert(objectQuery(this.props, 'widgetProps', 'transforms'));
  return (
    <ThemeWrapper>
      {/* <DLPWidgetView {...props} /> */}
      {/* {group.properties.map((property, j) => {
        if (property.show === false) {
          return null;
        }
        // Check if a field is present to display the error contextually
        const errorObjs =
          errors && errors.hasOwnProperty(property.name)
            ? errors[property.name]
            : null;
        if (errorObjs) {
          // Mark error as used
          newUsedErrors[property.name] = errors[property.name];
        }
        return (
          <PropertyRow
            key={`${property.name}-${j}`}
            widgetProperty={property}
            pluginProperty={pluginProperties[property.name]}
            value={values[property.name]}
            onChange={changeParentHandler}
            extraConfig={extraConfig}
            disabled={disabled}
            errors={errorObjs}
          />
        );
      })} */}
    </ThemeWrapper>
  );
}

(DLPWidget as any).propTypes = WIDGET_PROPTYPES;
