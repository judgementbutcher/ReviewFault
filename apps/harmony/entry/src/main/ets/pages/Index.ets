import { common } from '@kit.AbilityKit';
import { promptAction } from '@kit.ArkUI';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { picker } from '@kit.CoreFileKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { AppRepository, DashboardSummary, StudyRow } from '../data/AppRepository';

@Entry
@Component
struct Index {
  private repository: AppRepository = AppRepository.shared();
  @State private ready: boolean = false;
  @State private screen: string = 'home';
  @State private summary: DashboardSummary = { overdue: 0, dueToday: 0, newItems: 0, estimatedMinutes: 0 };
  @State private current?: StudyRow = undefined;
  @State private revealed: boolean = false;
  @State private reviewStartedAt: number = 0;
  @State private templateIndex: number = 0;
  @State private subjectIndex: number = 0;
  @State private prompt: string = '';
  @State private answer: string = '';
  @State private structured: string = '';
  @State private sourceName: string = '';
  @State private wrongStep: string = '';
  @State private keyHint: string = '';
  @State private errorReasonIndex: number = 0;
  @State private searchQuery: string = '';
  @State private library: StudyRow[] = [];
  @State private currentMediaPaths: string[] = [];
  @State private shownHintCount: number = 0;

  async aboutToAppear(): Promise<void> {
    try {
      const context = AppStorage.get<common.UIAbilityContext>('abilityContext');
      if (context === undefined) {
        throw new Error('无法获取应用上下文');
      }
      await this.repository.initialize(context);
      this.ready = true;
      await this.refreshHome();
    } catch (error) {
      promptAction.showToast({ message: `启动失败：${(error as Error).message}`, duration: 5000 });
    }
  }

  async refreshHome(): Promise<void> {
    const now = Math.floor(Date.now() / 1000);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    this.summary = await this.repository.dashboard(now, Math.floor(today.getTime() / 1000));
    this.screen = 'home';
  }

  async beginReview(): Promise<void> {
    this.current = await this.repository.nextForReview(Math.floor(Date.now() / 1000));
    if (this.current === undefined) {
      promptAction.showToast({ message: '当前没有到期内容，可以先新建一张卡片' });
      return;
    }
    this.reviewStartedAt = Math.floor(Date.now() / 1000);
    this.currentMediaPaths = await this.repository.mediaPaths(this.current.id);
    this.revealed = false;
    this.shownHintCount = 0;
    this.screen = 'review';
  }

  async score(rating: number, mathResult?: string): Promise<void> {
    if (this.current === undefined) {
      return;
    }
    try {
      const reviewedAt = Math.floor(Date.now() / 1000);
      const result = await this.repository.review(
        this.current, rating, reviewedAt, reviewedAt - this.reviewStartedAt, mathResult);
      promptAction.showToast({ message: `已保存，下次约 ${this.formatInterval(result.scheduledDays)} 后` });
      await this.beginReview();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async saveMemory(): Promise<void> {
    const templates: string[] = ['qa', 'cloze', 'layered_hint', 'enumeration', 'comparison'];
    const subjects: string[] = ['data_structures', 'computer_organization', 'operating_systems', 'computer_networks'];
    const lines = this.structured.split('\n').map((value) => value.trim()).filter((value) => value.length > 0);
    try {
      this.validateMemory(templates[this.templateIndex], lines);
      await this.repository.createMemoryCard(
        templates[this.templateIndex], subjects[this.subjectIndex], this.prompt, this.answer, lines);
      this.clearEditor();
      promptAction.showToast({ message: '408 卡片已保存' });
      await this.refreshHome();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async saveMath(): Promise<void> {
    try {
      await this.repository.createMathProblem(this.prompt, this.sourceName);
      this.clearEditor();
      promptAction.showToast({ message: '数学题已保存' });
      await this.refreshHome();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async saveMathDetails(): Promise<void> {
    if (this.current === undefined) return;
    const reasons: (string | undefined)[] = [undefined, 'concept', 'approach', 'calculation',
      'misread', 'forgotten_fact', 'timeout', 'other'];
    try {
      await this.repository.updateMathDetails(this.current.id, this.answer, this.wrongStep,
        this.keyHint, reasons[this.errorReasonIndex]);
      promptAction.showToast({ message: '错题复盘已保存' });
      await this.beginReview();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async saveMemoryEdit(): Promise<void> {
    if (this.current === undefined) return;
    try {
      await this.repository.updateMemoryCard(this.current.id, this.prompt, this.answer);
      promptAction.showToast({ message: '记忆卡已更新' });
      this.searchQuery = '';
      await this.showLibrary();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async pickMathImage(): Promise<void> {
    try {
      const options = new photoAccessHelper.PhotoSelectOptions();
      options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
      options.maxSelectNumber = 5;
      const picker = new photoAccessHelper.PhotoViewPicker();
      const picked = await picker.select(options);
      if (picked.photoUris.length === 0) return;
      const context = AppStorage.get<common.UIAbilityContext>('abilityContext');
      if (context === undefined) throw new Error('无法获取应用上下文');
      await this.repository.createMathProblemFromImages(context, picked.photoUris, '');
      promptAction.showToast({ message: `${picked.photoUris.length} 张题面已保存，稍后可直接重做` });
      await this.refreshHome();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async captureMathImage(): Promise<void> {
    try {
      const context = AppStorage.get<common.UIAbilityContext>('abilityContext');
      if (context === undefined) throw new Error('无法获取应用上下文');
      const profile: cameraPicker.PickerProfile = {
        cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK,
      };
      const result = await cameraPicker.pick(
        context, [cameraPicker.PickerMediaType.PHOTO], profile);
      if (result.resultCode !== 0 || result.resultUri.length === 0) return;
      await this.repository.createMathProblemFromImage(context, result.resultUri, '');
      promptAction.showToast({ message: '题面照片已保存，稍后可直接重做' });
      await this.refreshHome();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async showLibrary(): Promise<void> {
    try {
      this.library = await this.repository.search(this.searchQuery);
      this.screen = 'library';
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async openLibraryDetail(row: StudyRow): Promise<void> {
    this.current = row;
    this.currentMediaPaths = await this.repository.mediaPaths(row.id);
    this.screen = 'libraryDetail';
  }

  async exportBackup(): Promise<void> {
    try {
      const context = AppStorage.get<common.UIAbilityContext>('abilityContext');
      if (context === undefined) throw new Error('无法获取应用上下文');
      const options = new picker.DocumentSaveOptions();
      const date = new Date().toISOString().slice(0, 10);
      options.newFileNames = [`ReviewFault-${date}.reviewfault`];
      const uris = await new picker.DocumentViewPicker(context).save(options);
      if (uris.length === 0) return;
      await this.repository.exportBackup(context, uris[0]);
      promptAction.showToast({ message: '完整备份已导出' });
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 4000 });
    }
  }

  async restoreBackup(): Promise<void> {
    try {
      const confirmed = await promptAction.showDialog({
        title: '从备份恢复？',
        message: '恢复会替换当前设备上的数据库和媒体。建议先导出当前备份。',
        buttons: [{ text: '取消', color: '#666666' }, { text: '选择备份', color: '#B3261E' }],
      });
      if (confirmed.index !== 1) return;
      const context = AppStorage.get<common.UIAbilityContext>('abilityContext');
      if (context === undefined) throw new Error('无法获取应用上下文');
      const uris = await new picker.DocumentViewPicker(context).select(new picker.DocumentSelectOptions());
      if (uris.length === 0) return;
      await this.repository.restoreBackup(context, uris[0]);
      promptAction.showToast({ message: '数据已恢复' });
      await this.refreshHome();
    } catch (error) {
      promptAction.showToast({ message: (error as Error).message, duration: 5000 });
    }
  }

  private subjectLabel(subject: string): string {
    if (subject === 'data_structures') return '408 · 数据结构';
    if (subject === 'computer_organization') return '408 · 计算机组成原理';
    if (subject === 'operating_systems') return '408 · 操作系统';
    if (subject === 'computer_networks') return '408 · 计算机网络';
    return subject;
  }

  private structuredItems(row: StudyRow): string[] {
    try { return JSON.parse(row.structuredJson) as string[]; } catch (_) { return []; }
  }

  private reviewPrompt(row: StudyRow): string {
    if (row.templateType !== 'cloze') return row.prompt;
    return row.prompt.replace(/\{\{c\d+::(.*?)(?:::[^}]*)?}}/g, '[…]');
  }

  private reviewAnswer(row: StudyRow): string {
    if (row.templateType === 'enumeration') {
      return this.structuredItems(row).map((value) => `• ${value}`).join('\n');
    }
    if (row.templateType === 'cloze') {
      const answers: string[] = [];
      const expression = /\{\{c\d+::(.*?)(?:::[^}]*)?}}/g;
      let match: RegExpExecArray | null = expression.exec(row.prompt);
      while (match !== null) {
        answers.push(match[1]);
        match = expression.exec(row.prompt);
      }
      return answers.join('\n');
    }
    return row.answer;
  }

  private validateMemory(template: string, lines: string[]): void {
    if (this.prompt.trim().length === 0) throw new Error('题干不能为空');
    if ((template === 'qa' || template === 'comparison') && this.answer.trim().length === 0) {
      throw new Error('答案不能为空');
    }
    if (template === 'cloze' && !this.prompt.includes('{{c1::')) {
      throw new Error('填空题干需要包含 {{c1::答案}} 标记');
    }
    if (template === 'layered_hint' && (this.answer.trim().length === 0 || lines.length === 0)) {
      throw new Error('分层提示卡需要答案和至少一层提示');
    }
    if (template === 'enumeration' && lines.length < 2) {
      throw new Error('枚举卡至少需要两个答案要点');
    }
  }

  private clearEditor(): void {
    this.prompt = '';
    this.answer = '';
    this.structured = '';
    this.sourceName = '';
    this.wrongStep = '';
    this.keyHint = '';
    this.errorReasonIndex = 0;
  }

  private formatInterval(days: number): string {
    if (days < 1 / 24) return `${Math.round(days * 24 * 60)} 分钟`;
    if (days < 1) return `${Math.round(days * 24)} 小时`;
    return `${Math.round(days)} 天`;
  }

  @Builder
  homeScreen() {
    Column({ space: 18 }) {
      Text('ReviewFault').fontSize(32).fontWeight(FontWeight.Bold).fontColor('#1E362B')
      Text('把今天该复习的交给算法').fontSize(15).fontColor('#555555')
      Column() {
        Text('今日学习').fontSize(22).fontWeight(FontWeight.Bold).fontColor('#1E362B')
        Text(`逾期 ${this.summary.overdue} · 到期 ${this.summary.dueToday} · 新内容 ${this.summary.newItems}`)
          .fontSize(16).margin({ top: 10 })
        Text(`预计 ${this.summary.estimatedMinutes} 分钟`).fontSize(14).fontColor('#777777').margin({ top: 5 })
        Button('开始复习').width('100%').margin({ top: 16 }).backgroundColor('#315C49')
          .onClick(() => this.beginReview())
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding(18)
      .backgroundColor('#FFFFFF')
      .borderRadius(14)
      Text('快速记录').fontSize(20).fontWeight(FontWeight.Bold).fontColor('#1E362B')
      Button('拍照录入数学错题').width('100%').onClick(() => this.captureMathImage())
      Button('从相册导入数学错题').width('100%').onClick(() => this.pickMathImage())
      Button('用文字录入数学题').width('100%').onClick(() => {
        this.clearEditor(); this.screen = 'newMath';
      })
      Button('新建 408 记忆卡').width('100%').onClick(() => {
        this.clearEditor(); this.screen = 'newMemory';
      })
      Button('浏览 / 搜索题库').width('100%').onClick(() => {
        this.searchQuery = ''; this.showLibrary();
      })
      Text('数据与备份').fontSize(20).fontWeight(FontWeight.Bold).fontColor('#1E362B').margin({ top: 8 })
      Button('导出完整备份').width('100%').onClick(() => this.exportBackup())
      Button('从备份恢复').width('100%').onClick(() => this.restoreBackup())
    }
  }

  @Builder
  reviewScreen() {
    if (this.current !== undefined) {
      Column({ space: 16 }) {
        Text(this.current.kind === 'math_problem' ? '数学 · 重做' : '408 · 主动回忆')
          .fontSize(14).fontColor('#466455')
        Text(this.current.kind === 'math_problem' ? '先独立完成，再看答案' : '先在脑中或纸上作答')
          .fontSize(28).fontWeight(FontWeight.Bold).fontColor('#1E362B')
        Column() { Text(this.current === undefined ? '' : this.reviewPrompt(this.current)).fontSize(19) }
          .alignItems(HorizontalAlign.Start).width('100%').padding(18)
          .backgroundColor('#FFFFFF').borderRadius(14)
        ForEach(this.currentMediaPaths, (relativePath: string) => {
          Image(`file://${AppStorage.get<common.UIAbilityContext>('abilityContext')?.filesDir}/${relativePath}`)
            .width('100%').height(360).objectFit(ImageFit.Contain).backgroundColor('#FFFFFF')
        }, (relativePath: string) => relativePath)
        if (this.current.kind === 'math_problem') {
          Button('补充解答、错因与关键提示').width('100%').onClick(() => {
            this.answer = this.current?.answer ?? '';
            this.wrongStep = '';
            this.keyHint = '';
            this.errorReasonIndex = 0;
            this.screen = 'mathDetails';
          })
        } else {
          Button('编辑记忆卡').width('100%').backgroundColor('#315C49').onClick(() => {
            this.prompt = this.current?.prompt ?? '';
            this.answer = this.current?.answer ?? '';
            this.screen = 'memoryEdit';
          })
        }
        if (this.current.templateType === 'layered_hint') {
          ForEach(this.structuredItems(this.current).slice(0, this.shownHintCount),
            (hint: string, index: number) => {
              Column() { Text(`提示 ${index + 1}：${hint}`).fontSize(15) }
                .alignItems(HorizontalAlign.Start).width('100%').padding(14)
                .backgroundColor('#FFFFFF').borderRadius(10)
            }, (hint: string, index: number) => `${index}-${hint}`)
          if (this.shownHintCount < this.structuredItems(this.current).length) {
            Button('显示一层提示').width('100%').onClick(() => this.shownHintCount++)
          }
        }
        if (!this.revealed) {
          Button('显示答案 / 提交作答').width('100%').backgroundColor('#315C49')
            .onClick(() => this.revealed = true)
        } else {
          Column() {
            Text('参考答案').fontSize(14).fontColor('#777777')
            Text(this.reviewAnswer(this.current).length === 0 ?
              '尚未填写解答；仍可按实际结果评分。' : this.reviewAnswer(this.current))
              .fontSize(17).margin({ top: 8 })
          }
          .alignItems(HorizontalAlign.Start).width('100%').padding(18)
          .backgroundColor('#FFFFFF').borderRadius(14)
          Text('这次完成得怎样？').fontSize(18).fontWeight(FontWeight.Bold)
          if (this.current.kind === 'math_problem') {
            Row({ space: 6 }) {
              Button('不会').layoutWeight(1).onClick(() => this.score(1, 'again'))
              Button('做错').layoutWeight(1).onClick(() => this.score(1, 'wrong'))
              Button('勉强做对').layoutWeight(1).onClick(() => this.score(2, 'effortful'))
              Button('熟练').layoutWeight(1).onClick(() => this.score(4, 'fluent'))
            }.width('100%')
          } else {
            Row({ space: 6 }) {
              Button('忘记').layoutWeight(1).onClick(() => this.score(1))
              Button('困难').layoutWeight(1).onClick(() => this.score(2))
              Button('正确').layoutWeight(1).onClick(() => this.score(3))
              Button('轻松').layoutWeight(1).onClick(() => this.score(4))
            }.width('100%')
          }
        }
        Button('返回首页').width('100%').onClick(() => this.refreshHome())
      }
    }
  }

  @Builder
  memoryEditor() {
    Column({ space: 14 }) {
      Text('新建 408 记忆卡').fontSize(28).fontWeight(FontWeight.Bold).fontColor('#1E362B')
      Select([{ value: '问答' }, { value: '填空' }, { value: '分层提示' }, { value: '枚举' }, { value: '对比' }])
        .selected(this.templateIndex).onSelect((index) => this.templateIndex = index)
      Select([{ value: '数据结构' }, { value: '计算机组成原理' }, { value: '操作系统' }, { value: '计算机网络' }])
        .selected(this.subjectIndex).onSelect((index) => this.subjectIndex = index)
      TextArea({ placeholder: '问题 / 带 {{c1::答案}} 的填空', text: this.prompt })
        .onChange((value) => this.prompt = value)
      TextArea({ placeholder: '答案', text: this.answer }).onChange((value) => this.answer = value)
      TextArea({ placeholder: '提示或枚举要点（每行一条）', text: this.structured })
        .onChange((value) => this.structured = value)
      Button('保存').width('100%').backgroundColor('#315C49').onClick(() => this.saveMemory())
      Button('取消').width('100%').onClick(() => this.refreshHome())
    }
  }

  @Builder
  mathEditor() {
    Column({ space: 14 }) {
      Text('录入数学错题').fontSize(28).fontWeight(FontWeight.Bold).fontColor('#1E362B')
      Text('先记录题面，解答和错因可在复习时补充。').fontSize(14).fontColor('#666666')
      TextArea({ placeholder: '题面（支持公式文本）', text: this.prompt })
        .height(180).onChange((value) => this.prompt = value)
      TextInput({ placeholder: '来源（可选）', text: this.sourceName })
        .onChange((value) => this.sourceName = value)
      Button('保存').width('100%').backgroundColor('#315C49').onClick(() => this.saveMath())
      Button('取消').width('100%').onClick(() => this.refreshHome())
    }
  }

  @Builder
  mathDetailsEditor() {
    Column({ space: 14 }) {
      Text('完善数学错题').fontSize(28).fontWeight(FontWeight.Bold).fontColor('#1E362B')
      Select([{ value: '未选择' }, { value: '概念不清' }, { value: '思路中断' },
        { value: '计算错误' }, { value: '审题错误' }, { value: '遗忘结论' },
        { value: '超时' }, { value: '其他' }])
        .selected(this.errorReasonIndex).onSelect((index) => this.errorReasonIndex = index)
      TextArea({ placeholder: '完整解答', text: this.answer }).height(150)
        .onChange((value) => this.answer = value)
      TextArea({ placeholder: '自己的关键错误步骤', text: this.wrongStep })
        .onChange((value) => this.wrongStep = value)
      TextArea({ placeholder: '下次看到题时必须想起的一句提示', text: this.keyHint })
        .onChange((value) => this.keyHint = value)
      Button('保存').width('100%').backgroundColor('#315C49').onClick(() => this.saveMathDetails())
      Button('取消').width('100%').onClick(() => this.beginReview())
    }
  }

  @Builder
  libraryScreen() {
    Column({ space: 14 }) {
      Text('我的题库').fontSize(28).fontWeight(FontWeight.Bold).fontColor('#1E362B')
      TextInput({ placeholder: '搜索题干、答案或来源', text: this.searchQuery })
        .onChange((value) => this.searchQuery = value)
      Button('搜索').width('100%').backgroundColor('#315C49').onClick(() => this.showLibrary())
      Text(`${this.library.length} 条内容`).fontSize(14).fontColor('#777777')
      ForEach(this.library, (row: StudyRow) => {
        Column({ space: 6 }) {
          Text(row.kind === 'math_problem' ? '数学错题' : this.subjectLabel(row.subject))
            .fontSize(13).fontColor('#466455')
          Text(row.prompt.length === 0 ? '图片题面' : row.prompt).fontSize(17).maxLines(3)
          Text(row.state === 0 ? '新内容' : `已复习 ${row.repetitions} 次`).fontSize(13).fontColor('#777777')
        }
        .alignItems(HorizontalAlign.Start).width('100%').padding(16)
        .backgroundColor('#FFFFFF').borderRadius(12)
        .onClick(() => this.openLibraryDetail(row))
      }, (row: StudyRow) => row.id)
      Button('返回首页').width('100%').onClick(() => this.refreshHome())
    }
  }

  @Builder
  libraryDetailScreen() {
    if (this.current !== undefined) {
      Column({ space: 14 }) {
        Text(this.current.kind === 'math_problem' ? '数学错题' : this.subjectLabel(this.current.subject))
          .fontSize(14).fontColor('#466455')
        Text(this.current.prompt.length === 0 ? '图片题面' : this.current.prompt)
          .fontSize(24).fontWeight(FontWeight.Bold).fontColor('#1E362B')
        ForEach(this.currentMediaPaths, (relativePath: string) => {
          Image(`file://${AppStorage.get<common.UIAbilityContext>('abilityContext')?.filesDir}/${relativePath}`)
            .width('100%').height(360).objectFit(ImageFit.Contain).backgroundColor('#FFFFFF')
        }, (relativePath: string) => relativePath)
        Column({ space: 6 }) {
          Text('答案 / 解答').fontSize(14).fontColor('#777777')
          Text(this.reviewAnswer(this.current).length === 0 ? '尚未填写' : this.reviewAnswer(this.current)).fontSize(17)
        }.alignItems(HorizontalAlign.Start).width('100%').padding(16)
          .backgroundColor('#FFFFFF').borderRadius(12)
        if (this.current.kind === 'math_problem') {
          Button('编辑错题复盘').width('100%').backgroundColor('#315C49').onClick(() => {
            this.answer = this.current?.answer ?? '';
            this.wrongStep = ''; this.keyHint = ''; this.errorReasonIndex = 0;
            this.screen = 'mathDetails';
          })
        } else {
          Button('编辑记忆卡').width('100%').backgroundColor('#315C49').onClick(() => {
            this.prompt = this.current?.prompt ?? '';
            this.answer = this.current?.answer ?? '';
            this.screen = 'memoryEdit';
          })
        }
        Button('返回题库').width('100%').onClick(() => this.showLibrary())
      }
    }
  }

  @Builder
  memoryEditScreen() {
    Column({ space: 14 }) {
      Text('编辑 408 记忆卡').fontSize(28).fontWeight(FontWeight.Bold).fontColor('#1E362B')
      TextArea({ placeholder: '题干', text: this.prompt }).height(140)
        .onChange((value) => this.prompt = value)
      TextArea({ placeholder: '答案', text: this.answer }).height(180)
        .onChange((value) => this.answer = value)
      Button('保存').width('100%').backgroundColor('#315C49').onClick(() => this.saveMemoryEdit())
      Button('取消').width('100%').onClick(() => this.showLibrary())
    }
  }

  build() {
    Stack() {
      Scroll() {
        if (!this.ready) {
          Column() { Text('正在初始化…').fontSize(18) }.padding(28)
        } else if (this.screen === 'home') {
          this.homeScreen()
        } else if (this.screen === 'review') {
          this.reviewScreen()
        } else if (this.screen === 'newMemory') {
          this.memoryEditor()
        } else if (this.screen === 'mathDetails') {
          this.mathDetailsEditor()
        } else if (this.screen === 'library') {
          this.libraryScreen()
        } else if (this.screen === 'libraryDetail') {
          this.libraryDetailScreen()
        } else if (this.screen === 'memoryEdit') {
          this.memoryEditScreen()
        } else {
          this.mathEditor()
        }
      }
      .width('100%')
      .height('100%')
      .padding({ left: 22, right: 22, top: 26, bottom: 34 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F7F5EF')
  }
}
