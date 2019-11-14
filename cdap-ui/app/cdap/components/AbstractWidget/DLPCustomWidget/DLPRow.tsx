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
import FormHelperText from '@material-ui/core/FormHelperText';
import InputLabel from '@material-ui/core/InputLabel';
import * as React from 'react';
import withStyles, { StyleRules } from '@material-ui/core/styles/withStyles';
import Select from '@material-ui/core/Select';
import MenuItem from '@material-ui/core/MenuItem';
import AbstractRow, {
  IAbstractRowProps,
} from 'components/AbstractWidget/AbstractMultiRowWidget/AbstractRow';
import MultiSelect, { IOption } from '../FormInputs/MultiSelect';
import { IWidgetProperty, IPluginProperty } from 'components/ConfigurationGroup/types';
import PropertyRow from 'components/ConfigurationGroup/PropertyRow';
import InputFieldDropdown from '../InputFieldDropdown';
import { IconButton } from '@material-ui/core';
import If from 'components/If';
import ExpandLessIcon from '@material-ui/icons/ExpandLess';
import ExpandMoreIcon from '@material-ui/icons/ExpandMore';
import FormControl from '@material-ui/core/FormControl';

const styles = (theme): StyleRules => {
  return {
    // ...AbstractRowStyles(theme),
    root: {
      minHeight: '50px',
      display: 'grid',
      gridTemplateColumns: '1fr auto auto auto',
      alignItems: 'end',
      '& > button': {
        width: '40px',
        height: '40px',
        margin: 'auto',
      },
    },
    inputContainer: {
      display: 'grid',
      gridTemplateColumns: '40px 1fr 30px 1fr 50px 1fr',
      gridGap: '10px',
    },
    disabled: {
      color: `${theme.palette.grey['50']}`,
    },
    separator: {
      textAlign: 'center',
    },
    transformContainer: {
      marginLeft: '20px',
      marginTop: '15px',
      marginBottom: '15px',
    },
    transformProperty: {
      '& > *': {
        padding: '15px 0px 10px',
      },
    },
  };
};

export interface ITransformProp {
  label: string;
  name: string;
  options: IWidgetProperty[];
  supportedTypes?: string[];
}
export type FilterOption = IOption | string;

interface IDLPRowProps extends IAbstractRowProps<typeof styles> {
  transforms: ITransformProp[];
  filters: FilterOption[];
  extraConfig: any;
}

interface IDLPRowState {
  field: string;
  transform: string;
  filter: string;
  transformProperties: object;
  expanded?: boolean;
}

type StateKeys = keyof IDLPRowState;

class DLPRow extends AbstractRow<IDLPRowProps, IDLPRowState> {
  public static defaultProps = {
    transforms: [],
    filters: [],
    extraConfig: '',
    expanded: false,
  };

  public state = {
    field: '',
    transform: '',
    filter: '',
    transformProperties: {},
    expanded: false,
  };

  /**
   * Sample input: fieldName:filterScrope(filter)
   */
  public componentDidMount() {
    try {
      const jsonString = this.props.value;
      console.log(jsonString);
      const j = JSON.parse(jsonString);
      this.setState({ ...this.state, ...j });
      console.log(j);
    } catch (error) {
      console.log('FF');
      console.log(error);
      this.setState({
        field: '',
        transform: '',
        filter: '',
        transformProperties: {},
      });
    }
  }

  private handleChangeMultiSelect = (type: StateKeys, e: string) => {
    if (type === 'filter') {
      const noneWasSelected: boolean = this.state.filter.includes('NONE');
      const noneIsSelected: boolean = e.includes('NONE');

      if (noneWasSelected && noneIsSelected) {
        e = e.replace('NONE,', '');
      } else if (!noneWasSelected && noneIsSelected) {
        e = 'NONE';
      }
    }

    this.handleChange(type, e);
  };

  private handleChangeSelect = (type: StateKeys, e) => {
    this.handleChange(type, e.target.value);
    this.handleChange('expanded', true);
  };

  private handleChangeTransformOptions(optionName, e) {
    const newOptions = {
      ...this.state.transformProperties,
      [optionName]: e[optionName],
    };
    this.handleChange('transformProperties', newOptions);
  }

  private handleChange = (type: StateKeys, value) => {
    this.setState(
      {
        [type]: value,
      } as Pick<IDLPRowState, StateKeys>,
      () => {
        const { transform, filter } = this.state;

        if (transform.length === 0 || filter.length === 0) {
          this.onChange('');
          return;
        }

        const updatedValue = this.state;
        // updatedValue.expanded = false;
        this.onChange(JSON.stringify(updatedValue));
      }
    );
  };

  public renderInput = () => {
    console.log(this.state);
    const filters = this.props.filters.map((option: FilterOption) => {
      if (typeof option === 'object') {
        return option;
      }

      return {
        id: option,
        label: option,
      };
    });

    const transforms = this.props.transforms;
    const inputFieldProps = {
      multiselect: true,
      supportedTypes: [],
    };
    if (this.state.transform !== '') {
      inputFieldProps.supportedTypes =
        transforms.filter((transform) => transform.name == this.state.transform)[0]
          .supportedTypes || [];
    }

    return (
      <React.Fragment>
        <div>
          <div className={this.props.classes.inputContainer}>
            <span className={this.props.classes.separator}>Apply</span>
            <FormControl>
              <Select
                classes={{ disabled: this.props.classes.disabled }}
                value={this.state.transform}
                onChange={this.handleChangeSelect.bind(this, 'transform')}
                displayEmpty={true}
                disabled={false}
                placeholder="TEST"
              >
                {transforms.map((option) => {
                  return (
                    <MenuItem value={option.name} key={option.name}>
                      {option.label}
                    </MenuItem>
                  );
                })}
              </Select>
            </FormControl>
            <span className={this.props.classes.separator}>on</span>
            <MultiSelect
              disabled={false}
              value={this.state.filter}
              widgetProps={{ options: filters }}
              onChange={this.handleChangeMultiSelect.bind(this, 'filter')}
            />
            <span className={this.props.classes.separator}>within</span>
            <InputFieldDropdown
              widgetProps={inputFieldProps}
              value={this.state.field}
              onChange={this.handleChangeMultiSelect.bind(this, 'field')}
              disabled={false}
              extraConfig={this.props.extraConfig}
            />
          </div>

          <div className={this.props.classes.transformContainer}>
            {transforms.map((transform) => {
              if (transform.name !== this.state.transform || !this.state.expanded) {
                return;
              }
              return (
                <React.Fragment>
                  <h6>{transform.label} properties</h6>
                  {transform.options.map((property, j) => {
                    const pluginProp: IPluginProperty = {
                      description: '',
                      macroSupported: false,
                      required: false,
                    };
                    if (property['widget-attributes']) {
                      if (property['widget-attributes'].macro) {
                        pluginProp.macroSupported = property['widget-attributes'].macro;
                      }
                      if (property['widget-attributes'].description) {
                        pluginProp.description = property['widget-attributes'].description;
                      }
                      if (property['widget-attributes'].required) {
                        pluginProp.required = property['widget-attributes'].required;
                      }
                    }

                    return (
                      <div className={this.props.classes.transformProperty}>
                        <PropertyRow
                          key={`${property.name}-${j}`}
                          widgetProperty={property}
                          pluginProperty={pluginProp}
                          value={this.state.transformProperties[property.name]}
                          onChange={this.handleChangeTransformOptions.bind(this, property.name)}
                          extraConfig={this.props.extraConfig}
                          disabled={false}
                        />
                      </div>
                    );
                  })}
                </React.Fragment>
              );
            })}
          </div>
        </div>

        <If condition={this.state.expanded}>
          <IconButton
            color="primary"
            onClick={() => {
              this.handleChange('expanded', !this.state.expanded);
            }}
          >
            <ExpandLessIcon />
          </IconButton>
        </If>

        <If condition={!this.state.expanded}>
          <IconButton
            color="primary"
            onClick={() => {
              this.handleChange('expanded', !this.state.expanded);
            }}
          >
            <ExpandMoreIcon />
          </IconButton>
        </If>
      </React.Fragment>
    );
  };
}

const StyledDLPRow = withStyles(styles)(DLPRow);
export default StyledDLPRow;
