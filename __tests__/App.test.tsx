import React from 'react';
import { BackHandler, NativeModules } from 'react-native';
import TestRenderer, { act } from 'react-test-renderer';
import App, { formatExpiry } from '../App';

jest.mock('@react-native-community/slider', () => 'NativeSlider');

jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return { SafeAreaProvider: View, SafeAreaView: View };
});
describe('phone companion', () => {
  let screen: TestRenderer.ReactTestRenderer;
  const openSettings = async () => {
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'settings-open' })[0]
        .props.onPress();
    });
  };
  beforeEach(() => {
    jest.useFakeTimers();
    NativeModules.GymAccess = {
      appVersion: '1.6.0',
      setScanBrightness: jest.fn(),
      watchSettings: jest.fn().mockResolvedValue({ size: 3, closeSeconds: 30 }),
      saveWatchSettings: jest.fn().mockResolvedValue(undefined),
      status: jest.fn().mockResolvedValue({
        signedIn: false,
        watchConnected: false,
        codeReady: false,
      }),
      preview: jest.fn().mockResolvedValue(null),
      signIn: jest.fn().mockResolvedValue(undefined),
      syncNow: jest.fn().mockResolvedValue(undefined),
      refresh: jest.fn().mockResolvedValue(undefined),
      signOut: jest.fn().mockResolvedValue(undefined),
      updateWatch: jest.fn().mockResolvedValue(undefined),
      watchUpdateStatus: jest.fn().mockResolvedValue(null),
    };
  });
  afterEach(async () => {
    if (screen!) {
      await act(async () => screen.unmount());
    }
    jest.useRealTimers();
  });
  it('shows the reported installed watch version separately from the bundled update', async () => {
    NativeModules.GymAccess.watchUpdateStatus.mockResolvedValue({
      version: '1.8.2',
      installedVersion: '1.7.0',
      message: 'Ready',
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    expect(
      JSON.stringify(
        screen.root.findAllByProps({ testID: 'watch-installed-version' })[0]
          .props.children,
      ),
    ).toContain('1.7.0');
    expect(JSON.stringify(screen.toJSON())).toContain(
      'Included watch version: 1.8.2',
    );
  });
  it('does not infer installation from the bundled watch version', async () => {
    NativeModules.GymAccess.watchUpdateStatus.mockResolvedValue({
      version: '1.8.2',
      message: 'Ready',
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    expect(
      JSON.stringify(
        screen.root.findAllByProps({ testID: 'watch-installed-version' })[0]
          .props.children,
      ),
    ).toContain('not reported yet');
  });
  it('boosts brightness only with a visible valid QR and restores it for settings and expiry', async () => {
    NativeModules.GymAccess.status.mockResolvedValue({
      signedIn: true,
      watchConnected: true,
      codeReady: true,
    });
    NativeModules.GymAccess.preview.mockResolvedValue({
      uri: 'synthetic',
      pixels: 246,
      refreshAt: Date.now() + 5000,
      expiresAt: Date.now() + 10000,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(NativeModules.GymAccess.setScanBrightness).toHaveBeenLastCalledWith(
      true,
    );
    await openSettings();
    expect(NativeModules.GymAccess.setScanBrightness).toHaveBeenLastCalledWith(
      false,
    );
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'settings-back' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.setScanBrightness).toHaveBeenLastCalledWith(
      true,
    );
    await act(async () => {
      jest.advanceTimersByTime(11000);
    });
    expect(NativeModules.GymAccess.setScanBrightness).toHaveBeenLastCalledWith(
      false,
    );
  });
  it('shows session expiry with a fresh sign-in action', async () => {
    NativeModules.GymAccess.status.mockResolvedValue({
      signedIn: false,
      sessionExpired: true,
      watchConnected: false,
      codeReady: false,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(JSON.stringify(screen.toJSON())).toContain(
      'Your PureGym session has expired',
    );
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'connection-action' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.signIn).toHaveBeenCalledTimes(1);
  });
  it('shows confirmed receipt independently of connection and requests sync explicitly', async () => {
    NativeModules.GymAccess.status.mockResolvedValue({
      signedIn: true,
      watchConnected: false,
      codeReady: true,
      codeSynced: true,
      settingsSynced: false,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(
      screen.root.findAllByProps({ testID: 'watch-disconnected' }).length,
    ).toBeGreaterThan(0);
    await openSettings();
    expect(
      JSON.stringify(
        screen.root.findAllByProps({ testID: 'watch-code-sync' })[0].props
          .children,
      ),
    ).toContain('Received by watch');
    expect(
      JSON.stringify(
        screen.root.findAllByProps({ testID: 'watch-settings-sync' })[0].props
          .children,
      ),
    ).toContain('Waiting for watch confirmation');
    expect(NativeModules.GymAccess.syncNow).not.toHaveBeenCalled();
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'watch-sync-now' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.syncNow).toHaveBeenCalledTimes(1);
  });
  it('saves the optional vibration and resets it off', async () => {
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'watch-close-warning' })[0]
        .props.onValueChange(true);
    });
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'watch-settings-save' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.saveWatchSettings).toHaveBeenLastCalledWith(
      3,
      30,
      true,
    );
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'watch-settings-reset' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.saveWatchSettings).toHaveBeenLastCalledWith(
      3,
      30,
      false,
    );
  });
  it('shows the installed phone version on both pages and exposes stepped sliders', async () => {
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(
      JSON.stringify(
        screen.root.findAllByProps({ testID: 'phone-app-version' })[0].props
          .children,
      ),
    ).toContain('1.6.0');
    await openSettings();
    const size = screen.root.findAllByProps({ testID: 'watch-size-slider' })[0]
      .props;
    const timer = screen.root.findAllByProps({
      testID: 'watch-timeout-slider',
    })[0].props;
    expect([size.minimumValue, size.maximumValue, size.step]).toEqual([
      1, 3, 1,
    ]);
    expect([timer.minimumValue, timer.maximumValue, timer.step]).toEqual([
      15, 120, 15,
    ]);
    expect(
      JSON.stringify(
        screen.root.findAllByProps({ testID: 'phone-app-version' })[0].props
          .children,
      ),
    ).toContain('1.6.0');
  });
  it('houses preferences and updater on a separate page and supports Android back', async () => {
    const listener = jest.spyOn(BackHandler, 'addEventListener');
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(
      screen.root.findAllByProps({ testID: 'watch-settings-save' }),
    ).toHaveLength(0);
    expect(
      screen.root.findAllByProps({ testID: 'watch-update-action' }),
    ).toHaveLength(0);
    await openSettings();
    expect(
      screen.root.findAllByProps({ testID: 'connection-action' }),
    ).toHaveLength(0);
    expect(
      screen.root.findAllByProps({ testID: 'watch-update-action' }).length,
    ).toBeGreaterThan(0);
    const handler = listener.mock.calls[
      listener.mock.calls.length - 1
    ][1] as () => boolean;
    await act(async () => {
      expect(handler()).toBe(true);
    });
    expect(
      screen.root.findAllByProps({ testID: 'connection-action' }).length,
    ).toBeGreaterThan(0);
    listener.mockRestore();
  });
  it('resets and saves defaults without signing out or refreshing the credential', async () => {
    NativeModules.GymAccess.watchSettings.mockResolvedValue({
      size: 1,
      closeSeconds: 120,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'watch-settings-reset' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.saveWatchSettings).toHaveBeenCalledWith(
      3,
      30,
      false,
    );
    expect(
      screen.root.findAllByProps({ testID: 'watch-size-slider' })[0].props
        .value,
    ).toBe(3);
    expect(
      screen.root.findAllByProps({ testID: 'watch-timeout-slider' })[0].props
        .value,
    ).toBe(30);
    expect(NativeModules.GymAccess.signOut).not.toHaveBeenCalled();
    expect(NativeModules.GymAccess.refresh).not.toHaveBeenCalled();
  });
  it('preserves the selected values if reset fails', async () => {
    NativeModules.GymAccess.watchSettings.mockResolvedValue({
      size: 2,
      closeSeconds: 60,
    });
    NativeModules.GymAccess.saveWatchSettings.mockRejectedValue(
      new Error('failed'),
    );
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'watch-settings-reset' })[0]
        .props.onPress();
    });
    expect(
      screen.root.findAllByProps({ testID: 'watch-size-slider' })[0].props
        .value,
    ).toBe(2);
    expect(JSON.stringify(screen.toJSON())).toContain(
      'Unable to reset watch settings.',
    );
  });
  it('loads saved settings and saves the selected size and timeout without refreshing a code', async () => {
    NativeModules.GymAccess.watchSettings.mockResolvedValue({
      size: 2,
      closeSeconds: 120,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    expect(
      screen.root.findAllByProps({ testID: 'watch-size-slider' })[0].props
        .value,
    ).toBe(2);
    expect(
      screen.root.findAllByProps({ testID: 'watch-timeout-slider' })[0].props
        .value,
    ).toBe(120);
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'watch-size-slider' })[0]
        .props.onValueChange(1);
    });
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'watch-timeout-slider' })[0]
        .props.onValueChange(15);
    });
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'watch-settings-save' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.saveWatchSettings).toHaveBeenCalledWith(
      1,
      15,
      false,
    );
    expect(NativeModules.GymAccess.refresh).not.toHaveBeenCalled();
    expect(screen.root.findAllByProps({ testID: 'watch-size-4' })).toHaveLength(
      0,
    );
  });
  it('keeps settings available for retry when saving fails', async () => {
    NativeModules.GymAccess.saveWatchSettings.mockRejectedValue(
      new Error('offline'),
    );
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'watch-settings-save' })[0]
        .props.onPress();
    });
    expect(JSON.stringify(screen.toJSON())).toContain(
      'Unable to save watch settings.',
    );
    expect(
      screen.root.findAllByProps({ testID: 'watch-settings-save' })[0].props
        .disabled,
    ).toBe(false);
  });
  it('sends a watch update independently of membership login', async () => {
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'watch-update-action' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.updateWatch).toHaveBeenCalledTimes(1);
    expect(NativeModules.GymAccess.signIn).not.toHaveBeenCalled();
    expect(NativeModules.GymAccess.refresh).not.toHaveBeenCalled();
  });
  it('shows sending progress while the native transfer is pending', async () => {
    let finish!: () => void;
    NativeModules.GymAccess.updateWatch.mockImplementation(
      () =>
        new Promise<void>(resolve => {
          finish = resolve;
        }),
    );
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    let pending!: Promise<void>;
    await act(async () => {
      pending = screen.root
        .findAllByProps({ testID: 'watch-update-action' })[0]
        .props.onPress();
    });
    expect(JSON.stringify(screen.toJSON())).toContain('Sending update...');
    expect(
      screen.root.findAllByProps({ testID: 'watch-update-action' })[0].props
        .disabled,
    ).toBe(true);
    await act(async () => {
      screen.root
        .findAllByProps({ testID: 'settings-back' })[0]
        .props.onPress();
    });
    expect(
      screen.root.findAllByProps({ testID: 'connection-action' })[0].props
        .disabled,
    ).toBe(false);
    await openSettings();
    NativeModules.GymAccess.watchUpdateStatus.mockResolvedValue({
      version: '0.1.6',
      message: 'Checking that PG Access is responding on the watch.',
    });
    await act(async () => {
      jest.advanceTimersByTime(5000);
    });
    expect(JSON.stringify(screen.toJSON())).toContain(
      'Checking that PG Access is responding',
    );
    await act(async () => {
      finish();
      await pending;
    });
    expect(
      screen.root.findAllByProps({ testID: 'watch-update-action' })[0].props
        .disabled,
    ).toBe(false);
  });
  it('shows an update failure beside the updater and allows retry', async () => {
    NativeModules.GymAccess.updateWatch.mockRejectedValue(
      new Error('Your watch was found but PG Access did not reply.'),
    );
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await openSettings();
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'watch-update-action' })[0]
        .props.onPress();
    });
    expect(JSON.stringify(screen.toJSON())).toContain(
      'PG Access did not reply',
    );
    expect(
      screen.root.findAllByProps({ testID: 'watch-update-action' })[0].props
        .disabled,
    ).toBe(false);
  });
  it('opens hosted login through the native bridge', async () => {
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(NativeModules.GymAccess.status).toHaveBeenCalled();
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'connection-action' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.signIn).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(screen.toJSON())).toContain('Connect PureGym');
  });
  it('shows native failures and allows a retry', async () => {
    NativeModules.GymAccess.signIn.mockRejectedValue(
      new Error('Unable to open sign-in.'),
    );
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'connection-action' })[0]
        .props.onPress();
    });
    expect(JSON.stringify(screen.toJSON())).toContain(
      'Unable to open sign-in.',
    );
    expect(
      screen.root.findAllByProps({ testID: 'connection-action' })[0].props
        .disabled,
    ).toBe(false);
  });
  it('offers refresh and sign out for a connected membership', async () => {
    NativeModules.GymAccess.status.mockResolvedValue({
      signedIn: true,
      watchConnected: true,
      codeReady: true,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(JSON.stringify(screen.toJSON())).toContain('PureGym connected');
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'connection-action' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.refresh).toHaveBeenCalledTimes(1);
    await act(async () => {
      await screen.root
        .findAllByProps({ testID: 'signout-action' })[0]
        .props.onPress();
    });
    expect(NativeModules.GymAccess.signOut).toHaveBeenCalledTimes(1);
  });
  it('shows the cached preview while update status is still pending', async () => {
    NativeModules.GymAccess.watchUpdateStatus.mockImplementation(
      () => new Promise(() => {}),
    );
    NativeModules.GymAccess.status.mockResolvedValue({
      signedIn: true,
      watchConnected: true,
      codeReady: true,
    });
    NativeModules.GymAccess.preview.mockResolvedValue({
      uri: 'data:image/png;base64,synthetic',
      pixels: 246,
      refreshAt: Date.now() + 30000,
      expiresAt: Date.now() + 60000,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(
      screen.root.findAllByProps({ testID: 'entry-code-preview' }).length,
    ).toBeGreaterThan(0);
    expect(JSON.stringify(screen.toJSON())).toContain('Expires in ');
    expect(NativeModules.GymAccess.refresh).not.toHaveBeenCalled();
  });
  it('hides a code as soon as its recorded expiry is reached', async () => {
    NativeModules.GymAccess.status.mockResolvedValue({
      signedIn: true,
      watchConnected: true,
      codeReady: true,
    });
    NativeModules.GymAccess.preview.mockResolvedValue({
      uri: 'data:image/png;base64,synthetic',
      pixels: 246,
      refreshAt: Date.now(),
      expiresAt: Date.now() + 1000,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    await act(async () => {
      jest.advanceTimersByTime(1000);
    });
    expect(
      screen.root.findAllByProps({ testID: 'entry-code-preview' }),
    ).toHaveLength(0);
    expect(JSON.stringify(screen.toJSON())).toContain('No valid entry code.');
  });
  it('never shows a preview after sign-out', async () => {
    NativeModules.GymAccess.status.mockResolvedValue({
      signedIn: false,
      watchConnected: true,
      codeReady: false,
    });
    NativeModules.GymAccess.preview.mockResolvedValue({
      uri: 'data:image/png;base64,synthetic',
      pixels: 246,
      refreshAt: Date.now() + 10000,
      expiresAt: Date.now() + 60000,
    });
    await act(async () => {
      screen = TestRenderer.create(<App />);
    });
    expect(
      screen.root.findAllByProps({ testID: 'entry-code-preview' }),
    ).toHaveLength(0);
  });
});

describe('expiry display', () => {
  it.each([
    [584690, '6d 18h 24m'],
    [86400, '1d 0h 0m'],
    [86399, '23h 59m'],
    [3600, '1h 0m'],
    [3599, '59m 59s'],
    [64, '1m 04s'],
    [1, '1s'],
    [0, '0s'],
    [-1, '0s'],
  ])('formats %s seconds as %s', (seconds, expected) => {
    expect(formatExpiry(seconds)).toBe(expected);
  });
});
