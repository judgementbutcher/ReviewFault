import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

const version = '0.2.3';
const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');

const contracts = [
  ['README', '../../README.md', `当前版本为 **${version}**`],
  ['CMake', '../../CMakeLists.txt', `VERSION ${version}`],
  ['Android', '../../apps/android/app/build.gradle.kts', `versionName = "${version}"`],
  ['Windows project', '../../apps/windows/ReviewFault/ReviewFault.csproj', `<Version>${version}</Version>`],
  ['Windows manifest', '../../apps/windows/ReviewFault/app.manifest', `version="${version}.0"`],
  ['verify workflow', '../../.github/workflows/verify.yml', `APP_VERSION: ${version}`],
  ['release workflow', '../../.github/workflows/release.yml', `APP_VERSION: ${version}`],
  ['release notes', `../../docs/release-v${version}.md`, `# ReviewFault v${version}`],
];

for (const [name, path, token] of contracts) {
  assert(read(path).includes(token), `${name} is not synchronized to ${version}`);
}

for (const repository of ['../../apps/windows/ReviewFault/Data/AppRepository.cs']) {
  assert(read(repository).includes(version), `${repository} backup metadata is stale`);
}

const release = read('../../.github/workflows/release.yml');
assert(release.includes('assembleRelease') && !release.includes('assembleDebug'),
  'release workflow must build the signed release APK');
assert(release.includes('ANDROID_KEYSTORE_BASE64'),
  'release workflow must require persistent Android signing credentials');
assert(release.includes('ANDROID_CERT_SHA256') && release.includes('apksigner') &&
  release.includes('--print-certs'),
  'release workflow must pin and verify the Android signing certificate');
assert.deepEqual(readdirSync(new URL('../../apps/', import.meta.url)).sort(),
  ['android', 'windows'], 'only supported platform clients may be present');
assert(release.includes('needs: [core, android, windows]'),
  'release workflow must be gated on both supported platform builds');
assert(release.includes('test "$GITHUB_REF_NAME" = "v$APP_VERSION"'),
  'release tag must match application metadata');
assert(release.includes('WindowsAppSDKSelfContained=true') &&
  release.includes('ReviewFault.Installer.wixproj'),
  'Windows release must be self-contained and build the MSI installer');
assert(release.includes('ReviewFault-windows-v${{ env.APP_VERSION }}-x64.msi'),
  'Windows MSI must be attached to the release');

const publishProfile = read('../../apps/windows/ReviewFault/Properties/PublishProfiles/win-x64.pubxml');
assert(publishProfile.includes('<SelfContained>true</SelfContained>') &&
  publishProfile.includes('<WindowsAppSDKSelfContained>true</WindowsAppSDKSelfContained>'),
  'Windows publish profile must include .NET and Windows App SDK runtimes');
const windowsProject = read('../../apps/windows/ReviewFault/ReviewFault.csproj');
assert(windowsProject.includes('ResolvedFileToPublish') &&
  windowsProject.includes('<RelativePath>reviewfault_core.dll</RelativePath>'),
  'Windows publish must include the native scheduler DLL');
const installer = read('../../apps/windows/ReviewFault.Installer/Package.wxs');
assert(installer.includes('WixUI_InstallDir') && installer.includes('MajorUpgrade'),
  'Windows MSI must provide guided installation and upgrade support');
const installerProject = read('../../apps/windows/ReviewFault.Installer/ReviewFault.Installer.wixproj');
assert(installerProject.includes('<SuppressIces>ICE03</SuppressIces>'),
  'Windows MSI must suppress only the known Windows App SDK ICE03 metadata false positive');

console.log(`Version ${version} contract tests passed`);
