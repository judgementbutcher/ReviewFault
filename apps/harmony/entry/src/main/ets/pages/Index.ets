import { UIAbilityContext } from '@kit.AbilityKit';
import { DashboardSummary, LocalDatabase, MathErrorDraft, MemoryCardDraft, StudyItem, SubjectInsight } from '../data/LocalDatabase';
import { SyncCoordinator } from '../sync/SyncCoordinator';

enum Destination { Today, Insights, Library, Add, Review, Settings }
interface InkPoint { x: number; y: number; }
interface InkStroke { tool: string; color: string; width: number; points: InkPoint[]; }
interface ScaleRow { term: string; exponent: string; magnitude: string; }

@Entry
@Component
struct Index {
  @State destination: Destination = Destination.Today;
  @State widthVp: number = 0;
  @State summary: DashboardSummary = { overdue: 0, dueToday: 0, newItems: 0,
    reviewsToday: 0, accuracyPercent: 0, streakDays: 0, totalReviews: 0,
    activeItems: 0, masteredItems: 0, reviewDays: [0, 0, 0, 0, 0, 0, 0],
    dueDays: [0, 0, 0, 0, 0, 0, 0], subjects: [] };
  @State items: StudyItem[] = [];
  @State current?: StudyItem;
  @State answerRevealed: boolean = false;
  @State prompt: string = '';
  @State answer: string = '';
  @State addKind: string = 'memory_card';
  @State archetype: string = 'concept';
  @State subject: string = 'operating_systems';
  @State knowledgePoint: string = '';
  @State pointsText: string = '';
  @State hintsText: string = '';
  @State mechanism: string = '';
  @State conditions: string = '';
  @State contrast: string = '';
  @State example: string = '';
  @State commonTrap: string = '';
  @State transferPrompt: string = '';
  @State mnemonic: string = '';
  @State firstAttempt: string = '';
  @State errorTrigger: string = '';
  @State errorReason: string = 'approach';
  @State keyHint: string = '';
  @State generalMethod: string = '';
  @State verification: string = '';
  @State targetSecondsText: string = '';
  @State sourceYearText: string = '';
  @State sourceTitle: string = '';
  @State sourceChapter: string = '';
  @State sourceLocator: string = '';
  @State sourceType: string = 'textbook';
  @State tagsText: string = '';
  @State recallDraft: string = '';
  @State reflection: string = '';
  @State confidence: number = 3;
  @State shownHints: number = 0;
  @State checkedPoints: boolean[] = [];
  @State responseCommitted: boolean = false;
  @State directReveal: boolean = false;
  @State search: string = '';
  @State message: string = '';
  @State endpoint: string = 'https://sync.reviewfault.app';
  @State email: string = '';
  @State password: string = '';
  @State invitation: string = '';
  @State accountId: string = '';
  @State pendingCount: number = 0;
  @State syncing: boolean = false;
  @State strokes: InkStroke[] = [];
  @State activeStroke?: InkStroke;
  @State inkTool: string = 'pen';
  @State inkColor: string = '#F2F4F7';
  private readonly canvas = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private database?: LocalDatabase;
  private sync?: SyncCoordinator;
  private reviewStartedAt: number = 0;

  aboutToAppear(): void { this.initialize(); }
  aboutToDisappear(): void { this.sync?.close(); }
  private async initialize(): Promise<void> {
    try {
      const context = getContext(this) as UIAbilityContext;
      this.database = new LocalDatabase(context); await this.database.open();
      this.sync = new SyncCoordinator(context, this.database); await this.sync.initialize();
      this.accountId = this.sync.state.accountId; this.pendingCount = this.sync.state.pendingCount;
      await this.refresh();
    } catch (error) { this.message = (error as Error).message; }
  }
  private async refresh(): Promise<void> {
    if (!this.database) return; this.summary = await this.database.dashboard();
    this.items = await this.database.items(this.search);
  }
  private expanded(): boolean { return this.widthVp >= 720; }
  private title(): string { return ['今日', '洞察', '题库', '添加', '复习', '设置'][this.destination]; }

  @Builder navigation(): void {
    Column({ space: 8 }) {
      Text('ReviewFault').fontSize(22).fontWeight(FontWeight.Bold).margin({ bottom: 12 })
      this.navButton('今日', Destination.Today); this.navButton('洞察', Destination.Insights)
      this.navButton('题库', Destination.Library)
      this.navButton('添加', Destination.Add); this.navButton('设置', Destination.Settings)
    }.width(196).height('100%').padding(14).backgroundColor('#121417')
  }
  @Builder navButton(label: string, target: Destination): void {
    Button(label).height(48).width('100%').type(ButtonType.Normal)
      .backgroundColor(this.destination === target ? '#2B2F34' : Color.Transparent).fontColor('#F2F4F7')
      .onClick(() => { this.destination = target; if (target === Destination.Review) this.startReview(); })
  }
  @Builder bottomNavigation(): void {
    Row() { this.bottomButton('今日', Destination.Today); this.bottomButton('洞察', Destination.Insights)
      this.bottomButton('题库', Destination.Library)
      this.bottomButton('添加', Destination.Add); this.bottomButton('设置', Destination.Settings) }
      .width('100%').height(64).padding({ left: 4, right: 4 }).backgroundColor('#121417')
  }
  @Builder bottomButton(label: string, target: Destination): void {
    Button(label).height(48).layoutWeight(1).type(ButtonType.Normal)
      .backgroundColor(this.destination === target ? '#2B2F34' : Color.Transparent).fontColor('#F2F4F7')
      .onClick(() => { this.destination = target; if (target === Destination.Review) this.startReview(); })
  }

  @Builder page(): void {
    Scroll() { Column({ space: 16 }) {
      Row() { Column() { Text(this.title()).fontSize(30).fontWeight(FontWeight.Bold)
        if (this.message.length > 0) Text(this.message).fontSize(14).fontColor('#E06C75').margin({ top: 4 }) }
      }.width('100%')
      if (this.destination === Destination.Today) this.todayPage()
      else if (this.destination === Destination.Insights) this.insightsPage()
      else if (this.destination === Destination.Library) this.libraryPage()
      else if (this.destination === Destination.Add) this.addPage()
      else if (this.destination === Destination.Review) this.reviewPage()
      else this.settingsPage()
    }.padding(24).alignItems(HorizontalAlign.Start).width('100%') }
    .layoutWeight(1).scrollBar(BarState.Auto)
  }
  @Builder todayPage(): void {
    Column({ space: 14 }) {
      Text('把今天该复习的交给算法').fontSize(16).fontColor('#A7ADB5')
      Column({ space: 12 }) { Text('今日学习').fontSize(22).fontWeight(FontWeight.Medium)
        Text(`逾期 ${this.summary.overdue}  ·  到期 ${this.summary.dueToday}  ·  新内容 ${this.summary.newItems}`)
        Button('开始专注轮次').height(50).onClick(() => { this.destination = Destination.Review; this.startReview(); })
      }.padding(18).width('100%').backgroundColor('#121417').borderRadius(8)
        .border({ width: 1, color: '#2A2E34' })
      Text('最近内容').fontSize(20).fontWeight(FontWeight.Medium)
      ForEach(this.items.slice(0, 5), (item: StudyItem) => {
        Row() { Column() { Text(item.prompt.length > 0 ? item.prompt : '图片题面').maxLines(2)
          Text(item.kind === 'math_problem' ? '数学错题' : '408 记忆卡').fontSize(13).fontColor('#A7ADB5') }
        }.padding(14).width('100%').backgroundColor('#121417').borderRadius(8)
          .border({ width: 1, color: '#2A2E34' })
      }, (item: StudyItem) => item.id)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder insightsPage(): void {
    Column({ space: 14 }) {
      Text('LEARNING SIGNALS').fontSize(13).fontWeight(FontWeight.Medium).fontColor('#D4F36A')
      Text('看见你的积累').fontSize(28).fontWeight(FontWeight.Bold)
      Text('趋势用来调整节奏，不用单次结果评价自己。').fontColor('#A7ADB5')
      Row({ space: 8 }) {
        this.metric('今日复习', this.summary.reviewsToday, '次')
        this.metric('近 7 日正确', this.summary.accuracyPercent, '%')
        this.metric('连续学习', this.summary.streakDays, '天')
      }.width('100%')
      this.chartPanel('复习活跃度', '过去 7 天完成的复习次数', this.summary.reviewDays, false, '#D4F36A')
      this.chartPanel('未来负载', '今天起 7 天的到期内容', this.summary.dueDays, true, '#C6CCD4')
      Column({ space: 10 }) {
        Text('知识库进度').fontSize(20).fontWeight(FontWeight.Medium)
        Text(`${this.summary.masteredItems} / ${this.summary.activeItems} 条熟练内容 · 累计 ${this.summary.totalReviews} 次复习`)
          .fontColor('#A7ADB5')
        Progress({ value: this.summary.masteredItems, total: Math.max(1, this.summary.activeItems), type: ProgressType.Linear })
          .color('#D4F36A').backgroundColor('#25292E').height(8).width('100%')
        Text('稳定度达到 14 天且至少复习 3 次，记为熟练。').fontSize(13).fontColor('#A7ADB5')
      }.padding(18).width('100%').backgroundColor('#121417').borderRadius(8)
        .border({ width: 1, color: '#2A2E34' })
      Column({ space: 10 }) {
        Text('学科分布').fontSize(20).fontWeight(FontWeight.Medium)
        if (this.summary.subjects.length === 0) Text('添加内容后，这里会出现学科分布。').fontColor('#A7ADB5')
        ForEach(this.summary.subjects, (subject: SubjectInsight) => {
          Row() { Text(this.subjectLabel(subject.subject)).layoutWeight(1)
            Text(`${subject.mastered}/${subject.total}`).fontColor('#A7ADB5') }
          Progress({ value: subject.mastered, total: Math.max(1, subject.total), type: ProgressType.Linear })
            .color(subject.subject === 'math' ? '#C6CCD4' : '#D4F36A')
            .backgroundColor('#25292E').height(6).width('100%')
        }, (subject) => subject.subject)
      }.padding(18).width('100%').backgroundColor('#121417').borderRadius(8)
        .border({ width: 1, color: '#2A2E34' })
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder metric(label: string, value: number, unit: string): void {
    Column({ space: 3 }) { Row() { Text(`${value}`).fontSize(26).fontWeight(FontWeight.Bold).fontColor('#D4F36A')
        Text(unit).fontSize(13).fontColor('#A7ADB5').margin({ left: 3, top: 10 }) }
      Text(label).fontSize(12).fontColor('#A7ADB5')
    }.layoutWeight(1).padding(14).backgroundColor('#121417').borderRadius(8)
      .border({ width: 1, color: '#2A2E34' })
  }
  @Builder chartPanel(title: string, subtitle: string, values: number[], future: boolean, color: string): void {
    Column({ space: 10 }) { Text(title).fontSize(20).fontWeight(FontWeight.Medium)
      Text(subtitle).fontSize(13).fontColor('#A7ADB5')
      Row({ space: 7 }) { ForEach(values, (value: number, index: number) => {
        Column({ space: 5 }) { Text(`${value}`).fontSize(11).fontColor('#A7ADB5')
          Blank().height(12 + 84 * value / this.maxValue(values)).width('72%').backgroundColor(color).borderRadius(7)
            .animation({ duration: 420, curve: Curve.EaseOut })
          Text(this.weekday(future ? index : index - 6)).fontSize(11).fontColor('#A7ADB5')
        }.layoutWeight(1).height(132).justifyContent(FlexAlign.End)
      }) }.width('100%')
    }.padding(18).width('100%').backgroundColor('#121417').borderRadius(8)
      .border({ width: 1, color: '#2A2E34' })
  }
  private maxValue(values: number[]): number {
    let result = 1; values.forEach(value => { if (value > result) result = value; }); return result;
  }
  private weekday(offset: number): string {
    const value = new Date(); value.setDate(value.getDate() + offset);
    return ['日', '一', '二', '三', '四', '五', '六'][value.getDay()];
  }
  private subjectLabel(subject: string): string {
    if (subject === 'math') return '数学'; if (subject === 'data_structures') return '数据结构';
    if (subject === 'computer_organization') return '计算机组成';
    if (subject === 'operating_systems') return '操作系统';
    if (subject === 'computer_networks') return '计算机网络'; return subject;
  }
  @Builder libraryPage(): void {
    Column({ space: 12 }) {
      TextInput({ text: this.search, placeholder: '搜索题干、考点、来源或标签' }).height(48)
        .onChange(value => { this.search = value; this.refresh(); })
      ForEach(this.items, (item: StudyItem) => {
        Column({ space: 7 }) {
          Row({ space: 8 }) {
            Text(item.kind === 'math_problem' ? '数学错题' : this.archetypeLabel(item.profile.archetype))
              .fontSize(12).fontColor('#D4F36A')
            Text(item.profile.knowledgePoint).fontSize(12).fontColor('#A7ADB5').maxLines(1)
          }
          Text(item.prompt.length > 0 ? item.prompt : '图片题面').fontSize(17).maxLines(3)
          Text(item.answer).fontSize(14).fontColor('#A7ADB5').maxLines(2)
          if (item.profile.sourceTitle.length > 0) {
            Text(this.sourceLine(item)).fontSize(12).fontColor('#A7ADB5').maxLines(1)
          }
          if (item.tags.length > 0) Text(item.tags.join('  ·  ')).fontSize(12).fontColor('#8F969F').maxLines(2)
          Text(item.dueAt <= Math.floor(Date.now() / 1000) ? '待复习' : `已复习 ${item.repetitions} 次`)
            .fontSize(12).fontColor('#D4F36A')
        }.padding(14).width('100%').backgroundColor('#121417').borderRadius(8)
          .border({ width: 1, color: '#2A2E34' })
      }, (item: StudyItem) => item.id)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder editorTitle(title: string): void {
    Text(title).fontSize(13).fontWeight(FontWeight.Medium).fontColor('#A7ADB5').margin({ top: 8 })
  }

  @Builder editorChoice(label: string, value: string, group: string): void {
    Button(label).height(38).layoutWeight(1).type(ButtonType.Normal).borderRadius(6)
      .fontSize(13).fontColor(this.choiceSelected(value, group) ? '#090A0C' : '#D8DCE2')
      .backgroundColor(this.choiceSelected(value, group) ? '#D4F36A' : '#1A1D21')
      .border({ width: 1, color: this.choiceSelected(value, group) ? '#D4F36A' : '#30343A' })
      .onClick(() => this.selectEditorChoice(value, group))
      .animation({ duration: 180, curve: Curve.EaseOut })
  }

  private choiceSelected(value: string, group: string): boolean {
    if (group === 'subject') return this.subject === value;
    if (group === 'source') return this.sourceType === value;
    if (group === 'kind') return this.addKind === value;
    if (group === 'reason') return this.errorReason === value;
    return this.archetype === value;
  }

  private selectEditorChoice(value: string, group: string): void {
    if (group === 'subject') this.subject = value;
    else if (group === 'source') this.sourceType = value;
    else if (group === 'kind') { this.addKind = value;
      this.sourceType = value === 'math_problem' ? 'practice' : 'textbook'; }
    else if (group === 'reason') this.errorReason = value;
    else this.archetype = value;
  }

  @Builder addPage(): void {
    Column({ space: 12 }) {
      Row({ space: 6 }) {
        this.editorChoice('408 记忆卡', 'memory_card', 'kind')
        this.editorChoice('数学错题', 'math_problem', 'kind')
      }.width('100%')
      if (this.addKind === 'memory_card') this.memoryEditor()
      else this.mathEditor()
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder memoryEditor(): void {
    Column({ space: 10 }) {
      Text('结构化记忆卡').fontSize(20).fontWeight(FontWeight.Medium)
      this.editorTitle('学科')
      Row({ space: 6 }) {
        this.editorChoice('数据结构', 'data_structures', 'subject')
        this.editorChoice('计算机组成', 'computer_organization', 'subject')
      }.width('100%')
      Row({ space: 6 }) {
        this.editorChoice('操作系统', 'operating_systems', 'subject')
        this.editorChoice('计算机网络', 'computer_networks', 'subject')
      }.width('100%')
      this.editorTitle('知识形式')
      Row({ space: 6 }) {
        this.editorChoice('概念', 'concept', 'archetype')
        this.editorChoice('对比', 'comparison', 'archetype')
        this.editorChoice('枚举', 'enumeration', 'archetype')
      }.width('100%')
      Row({ space: 6 }) {
        this.editorChoice('量级映射', 'scale_mapping', 'archetype')
        this.editorChoice('公式规则', 'formula_rule', 'archetype')
        this.editorChoice('过程', 'process', 'archetype')
      }.width('100%')

      this.editorTitle('考点 / 知识点')
      TextInput({ text: this.knowledgePoint, placeholder: '如：性能指标、补码表示' })
        .onChange(value => this.knowledgePoint = value)
      this.editorTitle('主动回忆问题')
      TextArea({ text: this.prompt, placeholder: this.promptPlaceholder() }).height(112)
        .onChange(value => this.prompt = value)
      this.editorTitle('核心答案')
      TextArea({ text: this.answer, placeholder: '只保留回答问题所需的核心结论' }).height(130)
        .onChange(value => this.answer = value)
      this.editorTitle('评分要点（每行一点）')
      TextArea({ text: this.pointsText, placeholder: this.pointsPlaceholder() }).height(126)
        .onChange(value => this.pointsText = value)
      this.editorTitle('分层提示（从弱到强，每行一层）')
      TextArea({ text: this.hintsText, placeholder: '关键词提示\n关系或范围提示\n接近答案的提示' }).height(104)
        .onChange(value => this.hintsText = value)

      if (this.archetype === 'concept' || this.archetype === 'comparison') {
        this.editorTitle('机制 / 原因')
        TextArea({ text: this.mechanism, placeholder: '为什么成立，影响链条是什么' }).height(92)
          .onChange(value => this.mechanism = value)
      }
      if (this.archetype === 'comparison') {
        this.editorTitle('对比边界')
        TextArea({ text: this.contrast, placeholder: '相同点、不同点、容易混淆之处' }).height(92)
          .onChange(value => this.contrast = value)
      }
      if (this.archetype === 'formula_rule' || this.archetype === 'concept') {
        this.editorTitle('适用条件与边界')
        TextArea({ text: this.conditions, placeholder: '变量范围、位数、前提和例外' }).height(92)
          .onChange(value => this.conditions = value)
      }
      if (this.archetype === 'formula_rule') {
        this.editorTitle('边界例子')
        TextArea({ text: this.example, placeholder: '代入一个正数、负数或边界值验证规则' }).height(92)
          .onChange(value => this.example = value)
      }
      this.editorTitle('常见陷阱')
      TextArea({ text: this.commonTrap, placeholder: '最容易答错或混淆的点' }).height(84)
        .onChange(value => this.commonTrap = value)
      this.editorTitle('迁移问题')
      TextArea({ text: this.transferPrompt, placeholder: '换一个条件后，能否仍然推出结论？' }).height(84)
        .onChange(value => this.transferPrompt = value)

      this.editorTitle('来源类型')
      Row({ space: 6 }) {
        this.editorChoice('教材', 'textbook', 'source')
        this.editorChoice('课程', 'course', 'source')
        this.editorChoice('真题', 'past_exam', 'source')
        this.editorChoice('笔记', 'notes', 'source')
      }.width('100%')
      TextInput({ text: this.sourceTitle, placeholder: '书名 / 课程 / 试卷' })
        .onChange(value => this.sourceTitle = value)
      Row({ space: 8 }) {
        TextInput({ text: this.sourceChapter, placeholder: '章节' }).layoutWeight(1)
          .onChange(value => this.sourceChapter = value)
        TextInput({ text: this.sourceLocator, placeholder: '页码 / 题号' }).layoutWeight(1)
          .onChange(value => this.sourceLocator = value)
      }.width('100%')
      TextInput({ text: this.sourceYearText, placeholder: '年份（可选）' }).type(InputType.Number)
        .onChange(value => this.sourceYearText = value)
      this.editorTitle('标签（逗号或换行分隔）')
      TextArea({ text: this.tagsText, placeholder: '核心概念, 高频考点, 易混淆' }).height(80)
        .onChange(value => this.tagsText = value)
      Button('保存结构化记忆卡').height(50).width('100%').type(ButtonType.Normal).borderRadius(6)
        .fontColor('#090A0C').backgroundColor('#D4F36A').onClick(() => this.createCard())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder mathEditor(): void {
    Column({ space: 10 }) {
      Text('数学错题诊断').fontSize(20).fontWeight(FontWeight.Medium)
      this.editorTitle('考点 / 方法')
      TextInput({ text: this.knowledgePoint, placeholder: '如：导数零点、线性方程组秩' })
        .onChange(value => this.knowledgePoint = value)
      this.editorTitle('原题题面')
      TextArea({ text: this.prompt, placeholder: '录入题目、条件与目标' }).height(126)
        .onChange(value => this.prompt = value)
      this.editorTitle('首次作答 / 出错步骤')
      TextArea({ text: this.firstAttempt, placeholder: '保留当时的路线和第一处错误' }).height(104)
        .onChange(value => this.firstAttempt = value)
      this.editorTitle('错因触发')
      TextArea({ text: this.errorTrigger, placeholder: '哪个条件、表征或决策触发了错误' }).height(84)
        .onChange(value => this.errorTrigger = value)
      this.editorTitle('错因分类')
      Row({ space: 6 }) {
        this.editorChoice('概念', 'concept', 'reason')
        this.editorChoice('方法', 'approach', 'reason')
        this.editorChoice('计算', 'calculation', 'reason')
        this.editorChoice('审题', 'misread', 'reason')
      }.width('100%')
      Row({ space: 6 }) {
        this.editorChoice('遗忘', 'forgotten_fact', 'reason')
        this.editorChoice('超时', 'timeout', 'reason')
        this.editorChoice('其他', 'other', 'reason')
      }.width('100%')
      this.editorTitle('标准解法')
      TextArea({ text: this.answer, placeholder: '完整但精炼的正确解法' }).height(126)
        .onChange(value => this.answer = value)
      this.editorTitle('关键提示')
      TextArea({ text: this.keyHint, placeholder: '能重新启动思路的最小提示' }).height(80)
        .onChange(value => this.keyHint = value)
      this.editorTitle('可迁移通法')
      TextArea({ text: this.generalMethod, placeholder: '同类题可复用的识别信号与步骤' }).height(104)
        .onChange(value => this.generalMethod = value)
      this.editorTitle('验算 / 自检')
      TextArea({ text: this.verification, placeholder: '代回、量纲、边界或另一种方法' }).height(84)
        .onChange(value => this.verification = value)
      this.editorTitle('迁移题提示')
      TextArea({ text: this.transferPrompt, placeholder: '改变参数、问法或条件后的变式方向' }).height(84)
        .onChange(value => this.transferPrompt = value)
      TextInput({ text: this.targetSecondsText, placeholder: '目标用时（秒，可选）' }).type(InputType.Number)
        .onChange(value => this.targetSecondsText = value)

      this.editorTitle('来源类型')
      Row({ space: 6 }) {
        this.editorChoice('练习', 'practice', 'source')
        this.editorChoice('真题', 'past_exam', 'source')
        this.editorChoice('教材', 'textbook', 'source')
        this.editorChoice('课程', 'course', 'source')
      }.width('100%')
      TextInput({ text: this.sourceTitle, placeholder: '资料名 / 试卷' }).onChange(value => this.sourceTitle = value)
      Row({ space: 8 }) {
        TextInput({ text: this.sourceChapter, placeholder: '章节' }).layoutWeight(1)
          .onChange(value => this.sourceChapter = value)
        TextInput({ text: this.sourceLocator, placeholder: '页码 / 题号' }).layoutWeight(1)
          .onChange(value => this.sourceLocator = value)
      }.width('100%')
      TextInput({ text: this.sourceYearText, placeholder: '年份（可选）' }).type(InputType.Number)
        .onChange(value => this.sourceYearText = value)
      this.editorTitle('标签（逗号或换行分隔）')
      TextArea({ text: this.tagsText, placeholder: '二刷, 易错, 方法迁移' }).height(80)
        .onChange(value => this.tagsText = value)
      Button('保存数学错题').height(50).width('100%').type(ButtonType.Normal).borderRadius(6)
        .fontColor('#090A0C').backgroundColor('#D4F36A').onClick(() => this.createMathCard())
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  private async createMathCard(): Promise<void> {
    if (!this.database) return;
    const draft: MathErrorDraft = { knowledgePoint: this.knowledgePoint, prompt: this.prompt,
      solution: this.answer, firstAttempt: this.firstAttempt, errorTrigger: this.errorTrigger,
      errorReason: this.errorReason, keyHint: this.keyHint, generalMethod: this.generalMethod,
      verification: this.verification, transferPrompt: this.transferPrompt,
      targetSeconds: this.numberValue(this.targetSecondsText), sourceType: this.sourceType,
      sourceTitle: this.sourceTitle, sourceChapter: this.sourceChapter, sourceLocator: this.sourceLocator,
      sourceYear: this.numberValue(this.sourceYearText), tags: this.mathTags() };
    try { await this.database.createMathError(draft); this.resetEditor();
      this.message = '数学错题与诊断已保存'; this.destination = Destination.Today;
      await this.refresh(); await this.runSync();
    } catch (error) { this.message = (error as Error).message; }
  }

  private async createCard(): Promise<void> {
    const draft: MemoryCardDraft = { templateType: this.templateType(), archetype: this.archetype,
      subject: this.subject, knowledgePoint: this.knowledgePoint, prompt: this.prompt, answer: this.answer,
      hints: this.lines(this.hintsText), answerPoints: this.lines(this.pointsText), mechanism: this.mechanism,
      conditions: this.conditions, contrast: this.contrast, example: this.example, commonTrap: this.commonTrap,
      transferPrompt: this.transferPrompt, mnemonic: this.mnemonic, structuredPayload: this.structuredPayload(),
      sourceType: this.sourceType, sourceTitle: this.sourceTitle, sourceChapter: this.sourceChapter,
      sourceLocator: this.sourceLocator, sourceYear: this.numberValue(this.sourceYearText),
      tags: this.scientificTags() };
    try { await this.database?.createScientificMemoryCard(draft); this.resetEditor();
      this.message = '记忆卡已保存'; this.destination = Destination.Today; await this.refresh(); await this.runSync();
    } catch (error) { this.message = (error as Error).message; }
  }

  private async startReview(): Promise<void> {
    this.current = await this.database?.nextForReview(); this.answerRevealed = false; this.strokes = [];
    this.recallDraft = ''; this.reflection = ''; this.confidence = 3; this.shownHints = 0;
    this.responseCommitted = false; this.directReveal = false;
    this.checkedPoints = this.current ? this.current.answerPoints.map(() => false) : [];
    this.reviewStartedAt = Math.floor(Date.now() / 1000);
    if (!this.current) this.message = '当前没有待复习内容';
  }

  @Builder reviewPage(): void {
    if (!this.current) { Column({ space: 12 }) { Text('当前没有待复习内容').fontSize(18)
      Button('返回今日').onClick(() => this.destination = Destination.Today) } }
    else {
      Column({ space: 14 }) { Text(this.current.kind === 'math_problem' ? '数学 · 专注重做' : '408 · 主动回忆')
          .fontColor('#D4F36A').fontWeight(FontWeight.Medium)
        Row({ space: 8 }) {
          Text(this.current.profile.knowledgePoint).fontSize(13).fontColor('#A7ADB5')
          if (this.current.tags.length > 0) Text(this.current.tags.slice(0, 3).join(' · '))
            .fontSize(12).fontColor('#858C95').maxLines(1)
        }
        Column() { Text(this.current.prompt.length > 0 ? this.current.prompt : '图片题面').fontSize(22) }
          .padding(18).width('100%').backgroundColor('#121417').borderRadius(8)
            .border({ width: 1, color: '#2A2E34' })
        if (this.current.kind === 'math_problem' && !this.answerRevealed) this.inkPad()
        if (!this.answerRevealed) {
          TextArea({ text: this.recallDraft, placeholder: this.current.kind === 'math_problem' ?
            '写下解题路线、关键式或最终结果' : '在看答案前写下你的回忆' }).height(112)
            .onChange(value => this.recallDraft = value)
          this.confidenceControl()
          if (this.current.hints.length > 0) {
            ForEach(this.current.hints.slice(0, this.shownHints), (hint: string, index: number) => {
              Text(`提示 ${index + 1}  ${hint}`).fontSize(14).fontColor('#C6CCD4')
                .padding(12).width('100%').backgroundColor('#181B1F').borderRadius(6)
            })
            Button(this.shownHints < this.current.hints.length ? `显示提示 ${this.shownHints + 1}` : '提示已全部显示')
              .height(44).width('100%').type(ButtonType.Normal).borderRadius(6)
              .enabled(this.shownHints < this.current.hints.length)
              .onClick(() => this.shownHints += 1)
          }
          Row({ space: 8 }) {
            Button('锁定作答并核对').height(50).layoutWeight(2).type(ButtonType.Normal).borderRadius(6)
              .enabled(this.recallDraft.trim().length > 0 || this.current.kind === 'math_problem')
              .fontColor('#090A0C').backgroundColor('#D4F36A').onClick(() => {
                this.responseCommitted = true; this.directReveal = false; this.answerRevealed = true;
              })
            Button('直接看答案').height(50).layoutWeight(1).type(ButtonType.Normal).borderRadius(6)
              .backgroundColor('#25292E').onClick(() => {
                this.responseCommitted = false; this.directReveal = true; this.answerRevealed = true;
              })
          }.width('100%')
        } else {
          Column({ space: 10 }) {
            Text('参考答案').fontSize(18).fontWeight(FontWeight.Medium)
            Text(this.current.answer.length > 0 ? this.current.answer : '尚未填写参考答案')
            this.reviewAnalysis(this.current)
          }.padding(16).width('100%').backgroundColor('#181B1F').borderRadius(8)
            .border({ width: 1, color: '#30343A' })
          if (this.current.answerPoints.length > 0) {
            Text('逐项核对').fontSize(14).fontWeight(FontWeight.Medium).fontColor('#A7ADB5')
            ForEach(this.current.answerPoints, (point: string, index: number) => {
              Button(`${this.checkedPoints[index] ? '✓' : '○'}  ${point}`).height(44).width('100%')
                .type(ButtonType.Normal).borderRadius(6).fontSize(14)
                .fontColor(this.checkedPoints[index] ? '#090A0C' : '#D8DCE2')
                .backgroundColor(this.checkedPoints[index] ? '#D4F36A' : '#1A1D21')
                .onClick(() => this.togglePoint(index))
            })
          }
          TextArea({ text: this.reflection, placeholder: '本次遗漏、误判或需要修正的思路' }).height(88)
            .onChange(value => this.reflection = value)
          if (this.current.kind === 'memory_card') {
            Button(`按证据提交 · ${this.ratingLabel(this.evidenceRating())}`).height(50).width('100%')
              .type(ButtonType.Normal).borderRadius(6).fontColor('#090A0C').backgroundColor('#D4F36A')
              .onClick(() => this.score(this.evidenceRating()))
          } else {
            Row({ space: 6 }) { ForEach(['不会', '做错', '勉强', '熟练'], (label: string, index: number) => {
              Button(label).height(46).layoutWeight(1).type(ButtonType.Normal).borderRadius(6)
                .onClick(() => this.score(index + 1))
            }) }.width('100%')
          }
        }
      }.width('100%').alignItems(HorizontalAlign.Start)
    }
  }

  @Builder confidenceControl(): void {
    Column({ space: 7 }) {
      Text(`作答前信心  ${this.confidence}/5`).fontSize(13).fontColor('#A7ADB5')
      Row({ space: 6 }) { ForEach([1, 2, 3, 4, 5], (value: number) => {
        Button(`${value}`).height(36).layoutWeight(1).type(ButtonType.Normal).borderRadius(6)
          .fontColor(this.confidence === value ? '#090A0C' : '#D8DCE2')
          .backgroundColor(this.confidence === value ? '#D4F36A' : '#1A1D21')
          .onClick(() => this.confidence = value)
      }) }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder reviewAnalysis(item: StudyItem): void {
    if (item.profile.firstAttempt.length > 0) this.analysisLine('首次作答', item.profile.firstAttempt)
    if (item.profile.errorTrigger.length > 0) this.analysisLine('错因触发', item.profile.errorTrigger)
    if (item.profile.mechanism.length > 0) this.analysisLine('机制', item.profile.mechanism)
    if (item.profile.conditions.length > 0) this.analysisLine('条件', item.profile.conditions)
    if (item.profile.contrast.length > 0) this.analysisLine('对比', item.profile.contrast)
    if (item.profile.example.length > 0) this.analysisLine('例子', item.profile.example)
    if (item.profile.commonTrap.length > 0) this.analysisLine('陷阱', item.profile.commonTrap)
    if (item.profile.generalMethod.length > 0) this.analysisLine('通法', item.profile.generalMethod)
    if (item.profile.verification.length > 0) this.analysisLine('验证', item.profile.verification)
    if (item.profile.transferPrompt.length > 0) this.analysisLine('迁移', item.profile.transferPrompt)
  }

  @Builder analysisLine(label: string, content: string): void {
    Column({ space: 3 }) {
      Text(label).fontSize(12).fontWeight(FontWeight.Medium).fontColor('#D4F36A')
      Text(content).fontSize(14).fontColor('#D8DCE2')
    }.alignItems(HorizontalAlign.Start).width('100%')
  }

  private async score(value: number): Promise<void> {
    if (!this.current || !this.database) return;
    const pointCount = this.current.answerPoints.length;
    const pointHits = this.checkedPoints.filter(value => value).length;
    let applied = value;
    if (this.current.kind === 'math_problem' && this.directReveal) applied = 1;
    else if (this.current.kind === 'math_problem' && this.shownHints > 0) applied = Math.min(applied, 3);
    try { const now = Math.floor(Date.now() / 1000); await this.database.review(this.current,
      this.current.kind === 'math_problem' ? applied - 1 : applied, now - this.reviewStartedAt,
      { hintLevel: this.shownHints, answerRevealed: this.directReveal, confidence: this.confidence,
        reflection: this.reviewReflection(), pointHits: pointCount > 0 ? pointHits : undefined,
        pointCount: pointCount > 0 ? pointCount : undefined });
      this.message = '复习已保存'; await this.refresh(); await this.startReview(); await this.runSync();
    } catch (error) { this.message = (error as Error).message; }
  }

  private lines(value: string): string[] {
    return value.split('\n').map(line => line.trim()).filter(line => line.length > 0);
  }

  private templateType(): string {
    if (this.archetype === 'comparison') return 'comparison';
    if (this.archetype === 'enumeration' || this.archetype === 'scale_mapping') return 'enumeration';
    if (this.archetype === 'process') return 'layered_hint';
    return 'qa';
  }

  private promptPlaceholder(): string {
    if (this.archetype === 'comparison') return '比较两个概念：定义、共同点和核心差异是什么？';
    if (this.archetype === 'scale_mapping') return '给出各 FLOPS 单位对应的 10 的幂与中文量级';
    if (this.archetype === 'formula_rule') return '写出规则，并说明正数、负数与边界条件';
    if (this.archetype === 'enumeration') return '列出全部要点，并说明分类标准';
    if (this.archetype === 'process') return '按顺序回忆该过程的关键阶段';
    return '用一个边界清楚的问题触发完整概念';
  }

  private pointsPlaceholder(): string {
    if (this.archetype === 'scale_mapping') return 'MFLOPS | 10^6 | 百万\nGFLOPS | 10^9 | 十亿';
    if (this.archetype === 'formula_rule') return '正数补码与原码相同\n负数补码为模减绝对值\n范围与位数对应正确';
    if (this.archetype === 'comparison') return '吞吐量是单位时间处理数量\n响应时间是单次请求总等待时间';
    return '一条可独立判断对错的答案要点\n另一条答案要点';
  }

  private structuredPayload(): string {
    const points = this.lines(this.pointsText);
    if (this.archetype === 'scale_mapping') {
      const rows: ScaleRow[] = [];
      points.forEach(value => { const parts = value.split('|').map(part => part.trim());
        rows.push({ term: parts[0] ?? '', exponent: parts[1] ?? '', magnitude: parts[2] ?? '' }); });
      return JSON.stringify({ rows: rows });
    }
    if (this.archetype === 'formula_rule') return JSON.stringify({
      formula: this.answer.trim(), conditions: this.lines(this.conditions), examples: this.lines(this.example),
    });
    if (this.archetype === 'process') return JSON.stringify({ steps: points });
    if (this.archetype === 'enumeration') return JSON.stringify({ items: points });
    return '{}';
  }

  private scientificTags(): string[] {
    const tags: string[] = [];
    this.tagsText.split(/[,，\n]/).map(value => value.trim()).filter(value => value.length > 0)
      .forEach(value => { if (!tags.includes(value)) tags.push(value); });
    const point = this.knowledgePoint.trim();
    const automatic = [`学科/${this.subjectLabel(this.subject)}`, `形式/${this.archetypeLabel(this.archetype)}`];
    if (point.length > 0) automatic.push(`考点/${point}`);
    if (this.sourceTitle.trim().length > 0) automatic.push(`来源/${this.sourceTitle.trim()}`);
    if (this.sourceChapter.trim().length > 0) automatic.push(`章节/${this.sourceChapter.trim()}`);
    automatic.forEach(value => { if (!tags.includes(value)) tags.push(value); });
    return tags.slice(0, 30);
  }

  private mathTags(): string[] {
    const tags: string[] = [];
    this.tagsText.split(/[,，\n]/).map(value => value.trim()).filter(value => value.length > 0)
      .forEach(value => { if (!tags.includes(value)) tags.push(value); });
    const automatic = ['学科/数学', '形式/错题', `错因/${this.errorReasonLabel(this.errorReason)}`];
    if (this.knowledgePoint.trim().length > 0) automatic.push(`考点/${this.knowledgePoint.trim()}`);
    if (this.sourceTitle.trim().length > 0) automatic.push(`来源/${this.sourceTitle.trim()}`);
    if (this.sourceChapter.trim().length > 0) automatic.push(`章节/${this.sourceChapter.trim()}`);
    automatic.forEach(value => { if (!tags.includes(value)) tags.push(value); });
    return tags.slice(0, 30);
  }

  private errorReasonLabel(value: string): string {
    if (value === 'concept') return '概念'; if (value === 'approach') return '方法';
    if (value === 'calculation') return '计算'; if (value === 'misread') return '审题';
    if (value === 'forgotten_fact') return '遗忘'; if (value === 'timeout') return '超时'; return '其他';
  }

  private numberValue(value: string): number | undefined {
    if (value.trim().length === 0) return undefined;
    const result = Number(value.trim()); return Number.isInteger(result) ? result : undefined;
  }

  private resetEditor(): void {
    this.prompt = ''; this.answer = ''; this.knowledgePoint = ''; this.pointsText = ''; this.hintsText = '';
    this.mechanism = ''; this.conditions = ''; this.contrast = ''; this.example = ''; this.commonTrap = '';
    this.transferPrompt = ''; this.mnemonic = ''; this.sourceTitle = ''; this.sourceChapter = '';
    this.sourceLocator = ''; this.sourceYearText = ''; this.tagsText = ''; this.firstAttempt = '';
    this.errorTrigger = ''; this.keyHint = ''; this.generalMethod = ''; this.verification = '';
    this.targetSecondsText = '';
  }

  private archetypeLabel(value: string): string {
    if (value === 'concept') return '概念'; if (value === 'comparison') return '对比';
    if (value === 'enumeration') return '枚举'; if (value === 'scale_mapping') return '量级映射';
    if (value === 'formula_rule') return '公式规则'; if (value === 'process') return '过程';
    if (value === 'math_error') return '数学错题'; return '问答';
  }

  private sourceLine(item: StudyItem): string {
    const values: string[] = [item.profile.sourceTitle];
    if (item.profile.sourceChapter.length > 0) values.push(item.profile.sourceChapter);
    if (item.profile.sourceLocator.length > 0) values.push(item.profile.sourceLocator);
    if (item.profile.sourceYear !== undefined) values.push(`${item.profile.sourceYear}`);
    return values.join(' · ');
  }

  private togglePoint(index: number): void {
    const values = this.checkedPoints.slice(); values[index] = !values[index]; this.checkedPoints = values;
  }

  private evidenceRating(): number {
    if (!this.current || this.directReveal || !this.responseCommitted) return 1;
    let rating = 2;
    if (this.current.answerPoints.length > 0) {
      const ratio = this.checkedPoints.filter(value => value).length / this.current.answerPoints.length;
      rating = ratio < 0.5 ? 1 : ratio < 0.8 ? 2 : ratio < 1 ? 3 : 4;
    } else if (this.confidence >= 4) rating = 3;
    if (this.shownHints > 0) rating = Math.min(rating, this.shownHints >= 2 ? 2 : 3);
    if (this.confidence <= 2) rating = Math.min(rating, 3);
    return rating;
  }

  private ratingLabel(value: number): string {
    return ['忘记', '困难', '正确', '轻松'][Math.max(1, Math.min(4, value)) - 1];
  }

  private reviewReflection(): string {
    const values: string[] = [];
    if (this.recallDraft.trim().length > 0) values.push(`作答草稿：${this.recallDraft.trim()}`);
    if (this.reflection.trim().length > 0) values.push(`复盘：${this.reflection.trim()}`);
    return values.join('\n\n');
  }

  @Builder inkPad(): void {
    Column({ space: 8 }) { Row({ space: 8 }) { Button('笔').onClick(() => this.inkTool = 'pen')
        Button('荧光').onClick(() => this.inkTool = 'highlighter')
        Button('橡皮').onClick(() => this.inkTool = 'eraser')
        Button('撤销').onClick(() => { this.strokes = this.strokes.slice(0, -1); this.renderInk(); })
        Button('清空').onClick(() => { this.strokes = []; this.renderInk(); }) }
      Row({ space: 8 }) { ForEach(['#F2F4F7', '#FF8C83', '#D4F36A', '#FFC66C'], (color: string) => {
        Button().width(44).height(44).backgroundColor(color).onClick(() => this.inkColor = color)
      }) }
      Canvas(this.canvas).width('100%').height(420).backgroundColor('#121417').borderRadius(8)
        .onReady(() => this.renderInk()).onTouch((event: TouchEvent) => this.handleInk(event))
    }.width('100%')
  }
  private handleInk(event: TouchEvent): void {
    const touch = event.touches[0] ?? event.changedTouches[0]; if (!touch) return;
    const point: InkPoint = { x: touch.x, y: touch.y };
    if (event.type === TouchType.Down) this.activeStroke = { tool: this.inkTool, color: this.inkColor,
      width: this.inkTool === 'highlighter' ? 14 : 5, points: [point] };
    else if (event.type === TouchType.Move && this.activeStroke) { this.activeStroke.points.push(point); this.renderInk(); }
    else if ((event.type === TouchType.Up || event.type === TouchType.Cancel) && this.activeStroke) {
      this.strokes = this.strokes.concat([this.activeStroke]); this.activeStroke = undefined; this.renderInk();
    }
  }
  private renderInk(): void {
    this.canvas.clearRect(0, 0, this.widthVp, 420); this.canvas.lineCap = 'round'; this.canvas.lineJoin = 'round';
    const values = this.activeStroke ? this.strokes.concat([this.activeStroke]) : this.strokes;
    values.forEach(stroke => { if (stroke.points.length === 0) return; this.canvas.beginPath();
      this.canvas.strokeStyle = stroke.tool === 'eraser' ? '#121417' : stroke.color;
      this.canvas.globalAlpha = stroke.tool === 'highlighter' ? 0.35 : 1; this.canvas.lineWidth = stroke.width;
      this.canvas.moveTo(stroke.points[0].x, stroke.points[0].y);
      stroke.points.slice(1).forEach(point => this.canvas.lineTo(point.x, point.y)); this.canvas.stroke(); });
    this.canvas.globalAlpha = 1;
  }
  @Builder settingsPage(): void {
    Column({ space: 12 }) { Text('账号与同步').fontSize(20).fontWeight(FontWeight.Medium)
      TextInput({ text: this.endpoint, placeholder: '同步服务地址' }).onChange(value => this.endpoint = value)
      if (this.accountId.length === 0) {
        TextInput({ text: this.email, placeholder: '邮箱' }).onChange(value => this.email = value)
        TextInput({ text: this.password, placeholder: '密码（至少 12 位）' }).type(InputType.Password)
          .onChange(value => this.password = value)
        TextInput({ text: this.invitation, placeholder: '邀请码' }).onChange(value => this.invitation = value)
        Row({ space: 8 }) { Button('注册').layoutWeight(1).onClick(() => this.register())
          Button('登录').layoutWeight(1).onClick(() => this.login()) }.width('100%')
      } else { Text(`账号 ${this.accountId.slice(0, 8)}… · 待上传 ${this.pendingCount} 项`)
        Row({ space: 8 }) { Button(this.syncing ? '同步中' : '立即同步').layoutWeight(1)
            .enabled(!this.syncing).onClick(() => this.runSync())
          Button('退出').layoutWeight(1).onClick(() => this.logout()) }.width('100%') }
      Divider(); Text('本地数据可在未登录和断网时继续使用。').fontColor('#A7ADB5')
    }.padding(18).width('100%').backgroundColor('#121417').borderRadius(8)
      .border({ width: 1, color: '#2A2E34' }).alignItems(HorizontalAlign.Start)
  }
  private async register(): Promise<void> { try { await this.sync?.register(this.endpoint, this.email,
    this.password, this.invitation); this.message = '注册申请已提交，请完成邮箱验证'; }
    catch (error) { this.message = (error as Error).message; } }
  private async login(): Promise<void> { try { await this.sync?.login(this.endpoint, this.email, this.password);
    this.accountId = this.sync?.state.accountId ?? ''; this.pendingCount = this.sync?.state.pendingCount ?? 0;
    this.message = '已登录并完成首次同步'; await this.refresh(); }
    catch (error) { this.message = (error as Error).message; } }
  private async runSync(): Promise<void> { if (!this.sync) return; this.syncing = true;
    try { await this.sync.sync(); this.pendingCount = this.sync.state.pendingCount; this.message = '同步完成';
      await this.refresh(); } catch (error) { this.message = (error as Error).message; } finally { this.syncing = false; } }
  private async logout(): Promise<void> { await this.sync?.logout(); this.accountId = ''; this.message = '已退出，本地数据仍保留'; }

  build() {
    Column() { if (this.expanded()) { Row() { this.navigation(); Divider().vertical(true).height('100%'); this.page() }.height('100%') }
      else { this.page(); Divider(); this.bottomNavigation() } }
      .width('100%').height('100%').backgroundColor('#090A0C')
      .onAreaChange((_oldValue: Area, newValue: Area) => this.widthVp = Number(newValue.width))
  }
}
