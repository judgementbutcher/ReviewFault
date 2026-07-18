import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { LocalDatabase } from '../data/LocalDatabase';

export default class EntryAbility extends UIAbility {
  onCreate(_want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, 'ReviewFault', 'EntryAbility created');
    new LocalDatabase(this.context).open().catch((error: Error) => {
      hilog.error(0x0000, 'ReviewFault', 'Database initialization failed: %{public}s', error.message);
    });
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index');
  }
}
