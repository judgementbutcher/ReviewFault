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
    'math_schedule_state', 'deleted_at', 'include_memory_cards',
    'include_math_problems', 'daily_new_memory_limit',
    'review_event_v3', 'algorithm_parameter_registry', 'scheduler_generation',
    'excludedItemIds',
  ]) {
    assert(repository.includes(token), `${platform.name} repository is missing ${token}`);
  }
  for (const token of [
    '搜索', '数学', '408', '备份', '恢复', '答案', '设置', '回收站',
    '本轮跳过', '提前结束本轮',
  ]) {
    assert(ui.includes(token), `${platform.name} UI is missing ${token}`);
  }
  for (const token of ['abi', 'review']) {
    assert(binding.toLowerCase().includes(token), `${platform.name} binding is missing ${token}`);
  }
  for (const token of ['memory', 'math', 'v2', 'v3']) {
    assert(binding.toLowerCase().includes(token), `${platform.name} v2 binding is missing ${token}`);
  }
}

for (const [name, repositoryPath, uiPath] of [
  ['Android', '../../apps/android/app/src/main/java/cn/reviewfault/app/data/AppDatabase.kt',
    '../../apps/android/app/src/main/java/cn/reviewfault/app/MainActivity.kt'],
  ['Windows', '../../apps/windows/ReviewFault/Data/AppRepository.cs',
    '../../apps/windows/ReviewFault/MainWindow.xaml.cs'],
  ['HarmonyOS', '../../apps/harmony/entry/src/main/ets/data/LocalDatabase.ets',
    '../../apps/harmony/entry/src/main/ets/pages/Index.ets'],
]) {
  const repository = readFileSync(new URL(repositoryPath, import.meta.url), 'utf8');
  const ui = readFileSync(new URL(uiPath, import.meta.url), 'utf8');
  for (const token of ['reviewstoday', 'accuracypercent', 'streakdays', 'mastereditems'])
    assert(repository.toLowerCase().includes(token), `${name} insights repository is missing ${token}`);
  for (const token of ['洞察', '复习活跃度', '未来负载', '知识库进度'])
    assert(ui.includes(token), `${name} insights UI is missing ${token}`);
  assert(ui.includes('07110F') || ui.includes('090A0C') || ui.includes('BackgroundBrush'),
    `${name} UI is missing the modern dark foundation`);
}

console.log('Platform source contract tests passed');
