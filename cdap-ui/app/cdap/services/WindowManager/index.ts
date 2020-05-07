/*
 * Copyright © 2019-2020 Cask Data, Inc.
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

import ee from 'event-emitter';
import 'whatwg-fetch';
import ifvisible from 'ifvisible.js';
import SessionTokenStore from 'services/SessionTokenStore';
const WINDOW_ON_BLUR = 'WINDOW_BLUR_EVENT';
const WINDOW_ON_FOCUS = 'WINDOW_FOCUS_EVENT';

class WindowManager {
  public eventemitter = ee(ee);
  private idleTimePingTimeout = null;
  private static DEFAULT_PING_INTERVAL = 300000;
  constructor() {
    if (window.parent.Cypress) {
      return;
    }
    if (ifvisible.now('hidden')) {
      this.onBlurEventHandler();
    }
    ifvisible.on('idle', () => {
      if (window.parent.Cypress) {
        return;
      }
      this.onBlurEventHandler();
    });
    ifvisible.on('wakeup', () => {
      if (window.parent.Cypress) {
        return;
      }
      this.onFocusHandler();
    });
    ifvisible.setIdleDuration(30);
  }

  /**
   * We need to ping the nodejs server for every ${DEFAULT_PING_INTERVAL}
   * to check for status. This is done when the page goes inactive.
   * This is needed for proxies that needs to authenticate requests every X minutes
   */
  private pingNodejs = () => {
    fetch('/ping', {
      headers: {
        'X-Requested-With': 'XMLHttpRequest',
        sessionToken: SessionTokenStore.getState(),
      },
    });
  };

  public onBlurEventHandler = () => {
    this.pingNodejs();
    this.idleTimePingTimeout = setInterval(
      this.pingNodejs.bind(this),
      WindowManager.DEFAULT_PING_INTERVAL
    );
    this.eventemitter.emit(WINDOW_ON_BLUR);
  };

  public onFocusHandler = () => {
    clearInterval(this.idleTimePingTimeout);
    this.eventemitter.emit(WINDOW_ON_FOCUS);
  };

  public isWindowActive = () => {
    if (window.parent.Cypress) {
      return true;
    }
    return ifvisible.now('active');
  };
}

export default new WindowManager();
export { WINDOW_ON_BLUR, WINDOW_ON_FOCUS };
