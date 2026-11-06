import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const output = resolve(root, 'artifacts/app-store');
const run = (args, options = {}) => execFileSync('xcrun', ['simctl', ...args], { encoding: 'utf8', timeout: 60000, ...options });
const available = JSON.parse(run(['list', 'devices', 'available', '--json']));
const device = process.env.SCREENSHOT_DEVICE ?? Object.values(available.devices).flat().find(d => d.name === 'iPhone 17 Pro Max')?.udid;
if (!device) throw new Error('Create an iPhone 17 Pro Max simulator or set SCREENSHOT_DEVICE to its UDID.');
let app = process.argv[2];
if (!app) {
  execFileSync('xcodebuild', ['-quiet', '-project', 'iosApp/alphagps.xcodeproj', '-scheme', 'alphagps', '-configuration', 'Debug', '-destination', 'generic/platform=iOS Simulator', '-derivedDataPath', resolve(here, '.build'), 'ARCHS=arm64', 'CODE_SIGNING_ALLOWED=NO', 'build'], { cwd: root, stdio: 'inherit' });
  app = resolve(here, '.build/Build/Products/Debug-iphonesimulator/alphagps.app');
}
if (!Object.values(available.devices).flat().find(d => d.udid === device && d.state === 'Booted')) run(['boot', device]);
run(['bootstatus', device, '-b'], { stdio: 'inherit', timeout: 180000 });
run(['install', device, resolve(app)]);
run(['ui', device, 'appearance', 'light']);
run(['status_bar', device, 'override', '--time', '9:41', '--dataNetwork', 'wifi', '--wifiMode', 'active', '--wifiBars', '3', '--batteryState', 'charged', '--batteryLevel', '100']);
try {
  for (const [locale, language] of [['en-US', 'en'], ['de-DE', 'de']]) {
    const destination = resolve(output, 'raw', locale);
    mkdirSync(destination, { recursive: true });
    for (const scenario of ['geotagging', 'reconnect', 'remote', 'multiple', 'privacy']) {
      console.log(`Capturing ${locale}/${scenario}`);
      run(['launch', '--terminate-running-process', device, 'com.saschl.cameragps', '-AppleLanguages', `(${language})`, '-AppleLocale', locale.replace('-', '_')], { env: { ...process.env, SIMCTL_CHILD_ALPHA_GPS_SCREENSHOT: scenario } });
      // Allow Compose resources, fonts and the first frame to finish loading.
      await new Promise(resolve => setTimeout(resolve, 3500));
      run(['io', device, 'screenshot', resolve(destination, `${scenario}.png`)]);
    }
  }
} finally {
  run(['status_bar', device, 'clear']);
  run(['terminate', device, 'com.saschl.cameragps']);
}
await import('./render.mjs');
