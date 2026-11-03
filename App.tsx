import React, { useCallback, useEffect, useRef, useState } from 'react';
import Slider from '@react-native-community/slider';
import {
  ActivityIndicator,
  AppState,
  BackHandler,
  NativeModules,
  Image,
  PixelRatio,
  useWindowDimensions,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

type Connection = {
  signedIn: boolean;
  sessionExpired?: boolean;
  codeSynced?: boolean;
  settingsSynced?: boolean;
  watchConnected: boolean;
  codeReady: boolean;
  expiresAt?: string;
  error?: string;
};
type Preview = {
  uri: string;
  pixels: number;
  refreshAt: number;
  expiresAt: number;
};

type WatchUpdateInfo = {
  version: string;
  message: string;
  installedVersion?: string | null;
};

type WatchDisplaySettings = {
  size: number;
  closeSeconds: number;
  closeWarning?: boolean;
};

const DEFAULT_WATCH_SETTINGS: WatchDisplaySettings = {
  size: 3,
  closeSeconds: 30,
  closeWarning: false,
};

type Bridge = {
  appVersion: string;
  setScanBrightness(enabled: boolean): void;
  watchSettings(): Promise<WatchDisplaySettings>;
  saveWatchSettings(
    size: number,
    closeSeconds: number,
    closeWarning: boolean,
  ): Promise<void>;
  syncNow(): Promise<void>;
  status(): Promise<Connection>;
  preview(maximumPixels: number): Promise<Preview | null>;
  signIn(): Promise<void>;
  refresh(): Promise<void>;
  signOut(): Promise<void>;
  updateWatch(): Promise<void>;
  watchUpdateStatus(): Promise<WatchUpdateInfo | null>;
};

export function formatExpiry(seconds: number): string {
  const total = Math.max(0, Math.ceil(seconds));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const remaining = total % 60;
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${String(remaining).padStart(2, '0')}s`;
  return `${remaining}s`;
}

export default function App() {
  const gym = NativeModules.GymAccess as Bridge;
  const [page, setPage] = useState<'entry' | 'settings'>('entry');
  useEffect(() => {
    if (page !== 'settings') return;
    const listener = BackHandler.addEventListener('hardwareBackPress', () => {
      setPage('entry');
      return true;
    });
    return () => listener.remove();
  }, [page]);
  const { width } = useWindowDimensions();
  const pixelRatio = PixelRatio.get();
  const previewPixels = Math.floor(
    Math.max(120, Math.min(width - 84, 340)) * pixelRatio,
  );
  const requestSequence = useRef(0);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [now, setNow] = useState(Date.now());
  const [connection, setConnection] = useState<Connection>({
    signedIn: false,
    watchConnected: false,
    codeReady: false,
  });
  const [displaySettings, setDisplaySettings] = useState<WatchDisplaySettings>(
    DEFAULT_WATCH_SETTINGS,
  );
  const [settingsLoaded, setSettingsLoaded] = useState(false);
  const [savingSettings, setSavingSettings] = useState(false);
  const [settingsMessage, setSettingsMessage] = useState('');
  const [settingsError, setSettingsError] = useState('');
  useEffect(() => {
    let active = true;
    gym
      .watchSettings()
      .then(value => {
        if (active) {
          setDisplaySettings(value);
          setSettingsLoaded(true);
        }
      })
      .catch(() => {
        if (active)
          setSettingsError(
            'Unable to read watch settings. Reopen the app to retry.',
          );
      });
    return () => {
      active = false;
    };
  }, [gym]);
  const saveSettings = async (values = displaySettings, reset = false) => {
    setSavingSettings(true);
    setSettingsError('');
    setSettingsMessage('');
    try {
      await gym.saveWatchSettings(
        values.size,
        values.closeSeconds,
        !!values.closeWarning,
      );
      await update();
      setDisplaySettings(values);
      setSettingsMessage(
        reset
          ? 'Defaults restored. Your watch will sync when connected.'
          : 'Saved on this phone. Your watch will sync when connected.',
      );
    } catch {
      setSettingsError(
        reset
          ? 'Unable to reset watch settings. Please retry.'
          : 'Unable to save watch settings. Please retry.',
      );
    } finally {
      setSavingSettings(false);
    }
  };
  const [sendingUpdate, setSendingUpdate] = useState(false);
  const [updateError, setUpdateError] = useState('');
  const [watchUpdate, setWatchUpdate] = useState<WatchUpdateInfo | null>(null);
  useEffect(() => {
    let active = true;
    const poll = async () => {
      try {
        const next = await gym.watchUpdateStatus();
        if (active) {
          setWatchUpdate(next);
        }
      } catch {
        /* Keep the last known update status. */
      }
    };
    poll();
    const timer = setInterval(poll, 5000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [gym]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const update = useCallback(async () => {
    const sequence = ++requestSequence.current;
    try {
      const [nextConnection, nextPreview] = await Promise.all([
        gym.status(),
        gym.preview(previewPixels),
      ]);
      if (sequence !== requestSequence.current) {
        return;
      }
      setConnection(nextConnection);
      setPreview(nextConnection.signedIn ? nextPreview : null);
    } catch {
      setError('Unable to read connection status.');
    }
  }, [gym, previewPixels]);
  useEffect(() => {
    update();
    const timer = setInterval(update, 3000);
    const clock = setInterval(() => setNow(Date.now()), 1000);
    const listener = AppState.addEventListener('change', state => {
      if (state === 'active') {
        update();
      }
    });
    return () => {
      clearInterval(timer);
      clearInterval(clock);
      listener.remove();
    };
  }, [update]);
  const run = async (action: () => Promise<void>) => {
    ++requestSequence.current;
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Please try again.');
    } finally {
      await update();
      setBusy(false);
    }
  };
  const sendWatchUpdate = async () => {
    setSendingUpdate(true);
    setUpdateError('');
    setWatchUpdate(previous => ({
      version: previous?.version || '',
      message: 'Connecting to watch...',
    }));
    try {
      await gym.updateWatch();
    } catch (e) {
      setUpdateError(
        e instanceof Error ? e.message : 'Unable to send watch update.',
      );
    } finally {
      try {
        const next = await gym.watchUpdateStatus();
        if (next) {
          setWatchUpdate(next);
        }
      } catch {
        /* Polling will retry. */
      }
      setSendingUpdate(false);
    }
  };
  const visiblePreview =
    connection.signedIn && preview !== null && preview.expiresAt > now
      ? preview
      : null;
  const scanBrightness = page === 'entry' && visiblePreview !== null;
  useEffect(() => {
    gym.setScanBrightness(scanBrightness);
    return () => gym.setScanBrightness(false);
  }, [gym, scanBrightness]);
  const secondsLeft = visiblePreview
    ? Math.max(0, Math.ceil((visiblePreview.expiresAt - now) / 1000))
    : 0;
  const expiryText = formatExpiry(secondsLeft);
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={styles.page}>
        <ScrollView
          key={page}
          testID={`${page}-page`}
          contentContainerStyle={styles.content}
        >
          {page === 'entry' ? (
            <View style={styles.header}>
              <View style={styles.brand}>
                <Image
                  source={require('./assets/branding/pg-access-logo-v3-monogram.png')}
                  style={styles.logo}
                  accessibilityLabel="PG Access logo"
                />
                <Text style={styles.brandName}>PG Access</Text>
              </View>
              <View style={styles.headerActions}>
                <Pressable
                  testID="settings-open"
                  accessibilityRole="button"
                  accessibilityLabel="Open settings"
                  style={styles.navButton}
                  onPress={() => setPage('settings')}
                >
                  <Text style={styles.navText}>Settings</Text>
                </Pressable>
              </View>
            </View>
          ) : (
            <View style={styles.settingsHeader}>
              <Pressable
                testID="settings-back"
                accessibilityRole="button"
                accessibilityLabel="Back to entry code"
                style={styles.navButton}
                onPress={() => setPage('entry')}
              >
                <Text style={styles.navText}>‹ Back</Text>
              </Pressable>
              <Text accessibilityRole="header" style={styles.cardTitle}>
                Settings
              </Text>
            </View>
          )}
          {page === 'entry' ? (
            <>
              <View style={styles.heading}>
                <Text style={styles.eyebrow}>YOUR GYM. ONE SCAN AWAY.</Text>
                <Text
                  style={
                    connection.signedIn ? styles.compactTitle : styles.title
                  }
                >
                  {connection.sessionExpired
                    ? 'Sign in again.'
                    : connection.signedIn
                    ? 'Ready to scan.'
                    : 'Your way in.'}
                </Text>
                <Text style={styles.intro}>
                  {connection.sessionExpired
                    ? 'Your PureGym session has expired. Connect PureGym again to refresh your entry code.'
                    : connection.signedIn
                    ? 'Your entry code, always within reach.'
                    : 'Connect your PureGym UK membership for quick access on your phone. Your watch is optional.'}
                </Text>
              </View>
              {connection.signedIn && !connection.watchConnected && (
                <Text testID="watch-disconnected" style={styles.note}>
                  Watch disconnected. Reconnect it to receive your latest code
                  and settings. An unexpired saved watch code can still be used.
                </Text>
              )}
              {connection.signedIn && (
                <View style={styles.codeCard}>
                  <View style={styles.codeHeader}>
                    <Text style={styles.codeTitle}>Your entry code</Text>
                    <Text style={styles.codeEyebrow}>PUREGYM UK</Text>
                  </View>
                  {visiblePreview ? (
                    <>
                      <View style={styles.codeImageFrame}>
                        <Image
                          testID="entry-code-preview"
                          accessibilityLabel="Your PureGym entry QR code"
                          source={{ uri: visiblePreview.uri }}
                          style={{
                            width: visiblePreview.pixels / pixelRatio,
                            height: visiblePreview.pixels / pixelRatio,
                          }}
                          resizeMode="center"
                        />
                      </View>
                      <View style={styles.expiryBlock}>
                        <Text style={styles.expiryLabel}>Expires in </Text>
                        <Text
                          testID="entry-code-expiry"
                          style={styles.expiryValue}
                        >
                          {expiryText}
                        </Text>
                      </View>
                      <Text style={styles.codeTiming}>
                        {visiblePreview.refreshAt <= now
                          ? 'Refresh available'
                          : 'Current code'}
                      </Text>
                    </>
                  ) : (
                    <Text
                      testID="entry-code-unavailable"
                      style={styles.codeTiming}
                    >
                      No valid entry code. Tap Refresh entry code below.
                    </Text>
                  )}
                  <Text style={styles.codeNote}>
                    Hold your screen towards the entrance scanner.
                  </Text>
                </View>
              )}
              <View style={styles.card}>
                <Text style={styles.cardTitle}>
                  {connection.signedIn
                    ? 'PureGym connected'
                    : 'Connect your membership'}
                </Text>
                <Text style={styles.body}>
                  {connection.signedIn
                    ? 'Your membership is linked and securely stored on this phone.'
                    : "Sign in on PureGym's own website. Your password stays with PureGym."}
                </Text>
                <View style={styles.row}>
                  <Text style={styles.label}>Watch (optional)</Text>
                  <Text style={styles.value}>
                    {connection.watchConnected ? 'Connected' : 'Not connected'}
                  </Text>
                </View>
                <View style={styles.row}>
                  <Text style={styles.label}>Entry code</Text>
                  <Text style={styles.value}>
                    {connection.codeReady ? 'Ready' : 'Not available'}
                  </Text>
                </View>
                {error || connection.error ? (
                  <Text accessibilityRole="alert" style={styles.error}>
                    {error || connection.error}
                  </Text>
                ) : null}
                <Pressable
                  testID="connection-action"
                  accessibilityRole="button"
                  disabled={busy}
                  style={[styles.button, busy && styles.disabled]}
                  onPress={() =>
                    run(() =>
                      connection.signedIn ? gym.refresh() : gym.signIn(),
                    )
                  }
                >
                  {busy ? (
                    <ActivityIndicator color="#111314" />
                  ) : (
                    <Text style={styles.buttonText}>
                      {connection.signedIn
                        ? 'Refresh entry code'
                        : 'Connect PureGym'}
                    </Text>
                  )}
                </Pressable>
                {connection.signedIn && (
                  <Pressable
                    testID="signout-action"
                    accessibilityRole="button"
                    disabled={busy}
                    style={styles.secondary}
                    onPress={() => run(() => gym.signOut())}
                  >
                    <Text style={styles.secondaryText}>Sign out</Text>
                  </Pressable>
                )}
              </View>
            </>
          ) : (
            <>
              <View style={styles.heading}>
                <Text style={styles.eyebrow}>MAKE IT YOURS</Text>
                <Text style={styles.compactTitle}>Your watch, your way.</Text>
                <Text style={styles.intro}>
                  Display preferences and app updates for your watch.
                </Text>
              </View>
              <View style={styles.card}>
                <Text style={styles.cardTitle}>
                  Watch connection &amp; sync
                </Text>
                <Text testID="watch-connection-status" style={styles.body}>
                  {connection.watchConnected
                    ? 'Watch connected'
                    : 'Watch disconnected ? reconnect to sync'}
                </Text>
                <Text testID="watch-code-sync" style={styles.body}>
                  Entry code:{' '}
                  {connection.codeReady
                    ? connection.codeSynced
                      ? 'Received by watch'
                      : 'Waiting for watch confirmation'
                    : 'No valid code ? connect PureGym or refresh'}
                </Text>
                <Text testID="watch-settings-sync" style={styles.body}>
                  Settings:{' '}
                  {connection.settingsSynced
                    ? 'Received by watch'
                    : 'Waiting for watch confirmation'}
                </Text>
                <Pressable
                  testID="watch-sync-now"
                  accessibilityRole="button"
                  disabled={busy}
                  style={[styles.outlineButton, busy && styles.disabled]}
                  onPress={() => run(() => gym.syncNow())}
                >
                  <Text style={styles.outlineButtonText}>
                    {busy ? 'Syncing...' : 'Sync now'}
                  </Text>
                </Pressable>
                <Text style={styles.note}>
                  Confirmation requires watch 1.7.0 or later. Saved changes will
                  sync when reconnected.
                </Text>
                {error || connection.error ? (
                  <Text accessibilityRole="alert" style={styles.error}>
                    {error || connection.error}
                  </Text>
                ) : null}
              </View>
              <View style={styles.card}>
                <View style={styles.sectionHeader}>
                  <Text style={styles.cardTitle}>Watch settings</Text>
                  <Text style={styles.optional}>OPTIONAL</Text>
                </View>
                <Text style={styles.body}>
                  Choose your QR size and how long the watch app stays open.
                </Text>
                <View style={styles.row}>
                  <Text style={styles.settingLabel}>QR size</Text>
                  <Text style={styles.settingValue}>
                    Size {displaySettings.size}
                  </Text>
                </View>
                <Slider
                  testID="watch-size-slider"
                  accessibilityLabel="Watch QR size"
                  accessibilityValue={{
                    min: 1,
                    max: 3,
                    now: displaySettings.size,
                    text: `Size ${displaySettings.size}`,
                  }}
                  minimumValue={1}
                  maximumValue={3}
                  step={1}
                  value={displaySettings.size}
                  disabled={!settingsLoaded || savingSettings}
                  minimumTrackTintColor="#D5FF45"
                  maximumTrackTintColor="#424C50"
                  thumbTintColor="#D5FF45"
                  style={styles.slider}
                  onValueChange={value => {
                    setDisplaySettings(current => ({
                      ...current,
                      size: Math.max(1, Math.min(3, Math.round(value))),
                    }));
                    setSettingsMessage('');
                  }}
                />
                <View style={styles.row}>
                  <Text style={styles.note}>Size 1</Text>
                  <Text style={styles.note}>Size 3</Text>
                </View>
                <View style={styles.row}>
                  <Text style={styles.settingLabel}>
                    Close automatically after
                  </Text>
                  <Text style={styles.settingValue}>
                    {formatExpiry(displaySettings.closeSeconds)}
                  </Text>
                </View>
                <Slider
                  testID="watch-timeout-slider"
                  accessibilityLabel="Watch auto-close time"
                  accessibilityValue={{
                    min: 15,
                    max: 120,
                    now: displaySettings.closeSeconds,
                    text: `${displaySettings.closeSeconds} seconds`,
                  }}
                  minimumValue={15}
                  maximumValue={120}
                  step={15}
                  value={displaySettings.closeSeconds}
                  disabled={!settingsLoaded || savingSettings}
                  minimumTrackTintColor="#D5FF45"
                  maximumTrackTintColor="#424C50"
                  thumbTintColor="#D5FF45"
                  style={styles.slider}
                  onValueChange={value => {
                    setDisplaySettings(current => ({
                      ...current,
                      closeSeconds: Math.max(
                        15,
                        Math.min(120, Math.round(value / 15) * 15),
                      ),
                    }));
                    setSettingsMessage('');
                  }}
                />
                <View style={styles.row}>
                  <Text style={styles.note}>15 seconds</Text>
                  <Text style={styles.note}>2 minutes</Text>
                </View>
                <Text style={styles.note}>
                  The timer starts each time you open the watch app. Size and
                  timer changes take effect when synced.
                </Text>
                <View style={styles.row}>
                  <Text style={styles.settingLabel}>
                    Vibrate before closing
                  </Text>
                  <Switch
                    testID="watch-close-warning"
                    accessibilityLabel="Vibrate before closing"
                    value={!!displaySettings.closeWarning}
                    disabled={!settingsLoaded || savingSettings}
                    onValueChange={value => {
                      setDisplaySettings(current => ({
                        ...current,
                        closeWarning: value,
                      }));
                      setSettingsMessage('');
                    }}
                  />
                </View>
                <Text style={styles.note}>
                  A short vibration 3 seconds before auto-close.
                </Text>
                {settingsMessage ? (
                  <Text testID="watch-settings-saved" style={styles.note}>
                    {settingsMessage}
                  </Text>
                ) : null}
                {settingsError ? (
                  <Text accessibilityRole="alert" style={styles.error}>
                    {settingsError}
                  </Text>
                ) : null}
                <Pressable
                  testID="watch-settings-save"
                  accessibilityRole="button"
                  disabled={!settingsLoaded || savingSettings}
                  style={[
                    styles.outlineButton,
                    (!settingsLoaded || savingSettings) && styles.disabled,
                  ]}
                  onPress={() => saveSettings()}
                >
                  <Text style={styles.outlineButtonText}>
                    {savingSettings ? 'Saving...' : 'Save watch settings'}
                  </Text>
                </Pressable>
              </View>
              <View style={styles.card}>
                <View style={styles.sectionHeader}>
                  <Text style={styles.cardTitle}>Watch updates</Text>
                  <Text style={styles.optional}>OPTIONAL</Text>
                </View>
                <Text style={styles.body}>
                  {watchUpdate?.version
                    ? `Included watch version: ${watchUpdate?.version}`
                    : 'Watch update included with this phone app.'}
                </Text>
                <Text testID="watch-installed-version" style={styles.body}>
                  {watchUpdate?.installedVersion
                    ? `Installed watch version (last reported): ${watchUpdate.installedVersion}`
                    : 'Installed watch version: not reported yet. Open the updated watch app to check.'}
                </Text>
                <Text testID="watch-update-status" style={styles.body}>
                  {watchUpdate?.message ||
                    'Install watch 0.1.4 once to enable this feature. Future updates can be sent from here.'}
                </Text>
                {updateError ? (
                  <Text accessibilityRole="alert" style={styles.error}>
                    {updateError}
                  </Text>
                ) : null}
                <Pressable
                  testID="watch-update-action"
                  accessibilityRole="button"
                  disabled={sendingUpdate}
                  style={[
                    styles.outlineButton,
                    sendingUpdate && styles.disabled,
                  ]}
                  onPress={sendWatchUpdate}
                >
                  <Text style={styles.outlineButtonText}>
                    {sendingUpdate ? 'Sending update...' : 'Update watch'}
                  </Text>
                </Pressable>
                <Text style={styles.note}>
                  Keep PG Access open on your watch while sending. Tap Update
                  ready, then Install, and confirm on the watch. The first time,
                  allow PG Access to install updates when prompted.
                </Text>
              </View>
              <View style={styles.card}>
                <Text style={styles.cardTitle}>Reset settings</Text>
                <Text style={styles.body}>
                  Restore QR size 3, the 30-second auto-close timer and
                  vibration off. Your membership stays connected.
                </Text>
                {settingsMessage.startsWith('Defaults restored') ? (
                  <Text style={styles.note}>{settingsMessage}</Text>
                ) : null}
                {settingsError.includes('reset') ? (
                  <Text accessibilityRole="alert" style={styles.error}>
                    {settingsError}
                  </Text>
                ) : null}
                <Pressable
                  testID="watch-settings-reset"
                  accessibilityRole="button"
                  disabled={!settingsLoaded || savingSettings}
                  style={[
                    styles.outlineButton,
                    (!settingsLoaded || savingSettings) && styles.disabled,
                  ]}
                  onPress={() => saveSettings(DEFAULT_WATCH_SETTINGS, true)}
                >
                  <Text style={styles.outlineButtonText}>
                    {savingSettings ? 'Please wait...' : 'Reset to defaults'}
                  </Text>
                </Pressable>
              </View>
            </>
          )}
          <Text testID="phone-app-version" style={styles.version}>
            Version {gym.appVersion}
          </Text>
          <Text style={styles.footer}>
            Independent personal project. Not affiliated with PureGym.
          </Text>
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#111314' },
  content: {
    padding: 20,
    paddingTop: 16,
    gap: 20,
    paddingBottom: 32,
    width: '100%',
    maxWidth: 560,
    alignSelf: 'center',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    flexWrap: 'wrap',
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    flexWrap: 'wrap',
  },
  settingsHeader: { flexDirection: 'row', alignItems: 'center', gap: 16 },
  navButton: {
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: 12,
    borderRadius: 12,
    backgroundColor: '#24292A',
  },
  navText: { color: '#D5FF45', fontSize: 13, fontWeight: '600' },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  logo: { width: 44, height: 44, borderRadius: 12 },
  brandName: {
    color: '#F5F7F8',
    fontSize: 19,
    fontWeight: '700',
    letterSpacing: -0.5,
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    backgroundColor: '#24292A',
    borderRadius: 30,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  statusDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#D5FF45',
  },
  inactiveDot: { backgroundColor: '#9CA5A8' },
  statusText: { color: '#DCE2E4', fontSize: 12, fontWeight: '600' },
  heading: { gap: 10, paddingTop: 12, paddingBottom: 4 },
  eyebrow: {
    fontSize: 10,
    letterSpacing: 1.8,
    color: '#D5FF45',
    fontWeight: '700',
  },
  title: {
    fontSize: 40,
    lineHeight: 43,
    letterSpacing: -1.7,
    fontWeight: '800',
    color: '#F5F7F8',
  },
  compactTitle: {
    fontSize: 28,
    lineHeight: 34,
    letterSpacing: -0.8,
    fontWeight: '800',
    color: '#F5F7F8',
  },
  intro: { fontSize: 15, lineHeight: 23, color: '#A8B1B5', maxWidth: 360 },
  codeCard: {
    padding: 16,
    borderRadius: 26,
    backgroundColor: '#FFFFFF',
    alignItems: 'center',
    gap: 12,
  },
  codeHeader: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    flexWrap: 'wrap',
    padding: 4,
  },
  codeTitle: { fontSize: 16, fontWeight: '700', color: '#111314' },
  codeEyebrow: {
    fontSize: 9,
    letterSpacing: 1.2,
    fontWeight: '700',
    color: '#667174',
  },
  codeImageFrame: { alignItems: 'center', justifyContent: 'center' },
  expiryBlock: {
    alignSelf: 'stretch',
    alignItems: 'center',
    backgroundColor: '#F1F4ED',
    padding: 14,
    borderRadius: 16,
    gap: 3,
  },
  expiryLabel: { color: '#55604B', fontSize: 12, fontWeight: '500' },
  expiryValue: {
    color: '#17200F',
    fontSize: 25,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
    letterSpacing: -0.5,
    textAlign: 'center',
  },
  codeTiming: {
    fontSize: 12,
    fontWeight: '600',
    color: '#4D575D',
    textAlign: 'center',
  },
  codeNote: {
    fontSize: 12,
    lineHeight: 18,
    color: '#667174',
    textAlign: 'center',
    paddingBottom: 4,
  },
  card: {
    padding: 20,
    borderRadius: 22,
    backgroundColor: '#1C2022',
    borderWidth: 1,
    borderColor: '#2C3235',
    gap: 16,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    flexWrap: 'wrap',
  },
  optional: {
    color: '#929EA3',
    fontSize: 9,
    letterSpacing: 1.3,
    fontWeight: '700',
  },
  cardTitle: {
    fontSize: 19,
    fontWeight: '700',
    color: '#F5F7F8',
    letterSpacing: -0.3,
  },
  body: { fontSize: 14, lineHeight: 22, color: '#A8B1B5' },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 12,
    flexWrap: 'wrap',
  },
  label: { color: '#929EA3', fontSize: 13 },
  value: { color: '#E6ECDE', fontSize: 13, fontWeight: '600' },
  button: {
    backgroundColor: '#D5FF45',
    borderRadius: 15,
    minHeight: 52,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 14,
  },
  buttonText: { color: '#111314', fontSize: 15, fontWeight: '700' },
  outlineButton: {
    borderColor: '#59652E',
    borderWidth: 1,
    borderRadius: 15,
    minHeight: 50,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 14,
  },
  outlineButtonText: { color: '#D5FF45', fontSize: 15, fontWeight: '700' },
  settingLabel: { color: '#E6ECDE', fontSize: 13, fontWeight: '600' },
  slider: { width: '100%', height: 44 },
  settingValue: {
    color: '#D5FF45',
    fontSize: 14,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
  },
  version: {
    color: '#A8B1B5',
    fontSize: 12,
    textAlign: 'center',
    marginTop: 4,
  },
  disabled: { opacity: 0.6 },
  secondary: { padding: 10, alignItems: 'center' },
  secondaryText: { color: '#A8B1B5', fontSize: 13 },
  note: { fontSize: 12, lineHeight: 19, color: '#929EA3' },
  footer: {
    fontSize: 11,
    lineHeight: 18,
    color: '#778388',
    textAlign: 'center',
    paddingHorizontal: 20,
  },
  error: { color: '#FFB4A9', lineHeight: 22 },
});
