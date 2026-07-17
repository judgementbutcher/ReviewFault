import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const platforms = [
  {
    name: 'Android',
    repository: '../../apps/android/app/src/main/java/cn/reviewfault/app/data/AppDatabase.kt',
    ui: '../../apps/android/app/src/main/java/cn/reviewfault/app/MainActivity.kt',
    binding: '../../apps/android/app/src/main/cpp/scheduler_jni.cpp',
  },
  {
    name: 'HarmonyOS',
    repository: '../../apps/harmony/entry/src/main/ets/data/AppRepository.ets',
    ui: '../../apps/harmony/entry/src/main/ets/pages/Index.ets',
    binding: '../../apps/harmony/entry/src/main/cpp/napi_scheduler.cpp',
  },
  {
    name: 'Windows',
    repository: '../../apps/windows/ReviewFault/Data/AppRepository.cs',
    ui: '../../apps/windows/ReviewFault/MainWindow.xaml.cs',
    binding: '../../apps/windows/ReviewFault/Core/NativeScheduler.cs',
  },
];

for (const platform of platforms) {
  const repository = readFileSync(new URL(platform.repository, import.meta.url), 'utf8');
  const ui = readFileSync(new URL(platform.ui, import.meta.url), 'utf8');
  const binding = readFileSync(new URL(platform.binding, import.meta.url), 'utf8');
  for (const token of [
    'review_log', 'attempt', 'memory_card', 'math_problem', 'scheduler_state',
    'foreign_key_check', 'integrity_check', 'reviewfault-backup',
    'learning_preferences', 'review_event_v2', 'memory_schedule_state',
    'math_schedule_state', 'deleted_at',
  ]) {
    assert(repository.includes(token), `${platform.name} repository is missing ${token}`);
  }
  for (const token of ['搜索', '数学', '408', '备份', '恢复', '答案', '设置', '回收站']) {
    assert(ui.includes(token), `${platform.name} UI is missing ${token}`);
  }
  for (const token of ['abi', 'review']) {
    assert(binding.toLowerCase().includes(token), `${platform.name} binding is missing ${token}`);
  }
  for (const token of ['memory', 'math', 'v2']) {
    assert(binding.toLowerCase().includes(token), `${platform.name} v2 binding is missing ${token}`);
  }
}

const harmonyRepository = readFileSync(new URL(
  '../../apps/harmony/entry/src/main/ets/data/AppRepository.ets', import.meta.url,
), 'utf8');
assert(harmonyRepository.includes('.beginTransaction()'));
assert(harmonyRepository.includes('.rollBack()'));
assert(!/executeSql\(['"`]BEGIN/i.test(harmonyRepository),
  'HarmonyOS executeSql must not be used for transaction control');

console.log('Platform source contract tests passed');
