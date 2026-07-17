import { common } from '@kit.AbilityKit';
import { AppRepository, DashboardSummary, LearningPreferences, StudyRow } from '../data/AppRepository';

export class ReviewFaultViewModel {
  private static singleton: ReviewFaultViewModel = new ReviewFaultViewModel();
  readonly repository: AppRepository = AppRepository.shared();

  static shared(): ReviewFaultViewModel {
    return ReviewFaultViewModel.singleton;
  }

  async initialize(context: common.UIAbilityContext): Promise<void> {
    await this.repository.initialize(context);
  }

  async dashboard(now: number, dayStart: number): Promise<DashboardSummary> {
    return this.repository.dashboard(now, dayStart);
  }

  async search(query: string, deletedOnly: boolean = false): Promise<StudyRow[]> {
    return this.repository.search(query, deletedOnly);
  }

  async learningPreferences(): Promise<LearningPreferences> {
    return this.repository.learningPreferences();
  }
}
