import { UIAbilityContext } from '@kit.AbilityKit';
import { DashboardSummary, LocalDatabase, StudyItem, SubjectInsight } from '../data/LocalDatabase';
import { SyncCoordinator } from '../sync/SyncCoordinator';

enum Destination { Today, Insights, Library, Add, Review, Settings }
interface InkPoint { x: number; y: number; }
interface InkStroke { tool: string; color: string; width: number; points: InkPoint[]; }

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
  @State inkColor: string = '#E5F0EC';
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
    }.width(196).height('100%').padding(14).backgroundColor('#101C19')
  }
  @Builder navButton(label: string, target: Destination): void {
    Button(label).height(48).width('100%').type(ButtonType.Normal)
      .backgroundColor(this.destination === target ? '#244E42' : Color.Transparent).fontColor('#E5F0EC')
      .onClick(() => { this.destination = target; if (target === Destination.Review) this.startReview(); })
  }
  @Builder bottomNavigation(): void {
    Row() { this.bottomButton('今日', Destination.Today); this.bottomButton('洞察', Destination.Insights)
      this.bottomButton('题库', Destination.Library)
      this.bottomButton('添加', Destination.Add); this.bottomButton('设置', Destination.Settings) }
      .width('100%').height(64).padding({ left: 4, right: 4 }).backgroundColor('#101C19')
  }
  @Builder bottomButton(label: string, target: Destination): void {
    Button(label).height(48).layoutWeight(1).type(ButtonType.Normal)
      .backgroundColor(this.destination === target ? '#244E42' : Color.Transparent).fontColor('#E5F0EC')
      .onClick(() => { this.destination = target; if (target === Destination.Review) this.startReview(); })
  }

  @Builder page(): void {
    Scroll() { Column({ space: 16 }) {
      Row() { Column() { Text(this.title()).fontSize(30).fontWeight(FontWeight.Bold)
        if (this.message.length > 0) Text(this.message).fontSize(14).fontColor('#8C3B32').margin({ top: 4 }) }
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
      Text('把今天该复习的交给算法').fontSize(16).fontColor('#B7C9C3')
      Column({ space: 12 }) { Text('今日学习').fontSize(22).fontWeight(FontWeight.Medium)
        Text(`逾期 ${this.summary.overdue}  ·  到期 ${this.summary.dueToday}  ·  新内容 ${this.summary.newItems}`)
        Button('开始专注轮次').height(50).onClick(() => { this.destination = Destination.Review; this.startReview(); })
      }.padding(18).width('100%').backgroundColor('#101C19').borderRadius(22)
        .border({ width: 1, color: '#345148' })
      Text('最近内容').fontSize(20).fontWeight(FontWeight.Medium)
      ForEach(this.items.slice(0, 5), (item: StudyItem) => {
        Row() { Column() { Text(item.prompt.length > 0 ? item.prompt : '图片题面').maxLines(2)
          Text(item.kind === 'math_problem' ? '数学错题' : '408 记忆卡').fontSize(13).fontColor('#B7C9C3') }
        }.padding(14).width('100%').backgroundColor('#101C19').borderRadius(18)
          .border({ width: 1, color: '#345148' })
      }, (item: StudyItem) => item.id)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder insightsPage(): void {
    Column({ space: 14 }) {
      Text('LEARNING SIGNALS').fontSize(13).fontWeight(FontWeight.Medium).fontColor('#78E8C1')
      Text('看见你的积累').fontSize(28).fontWeight(FontWeight.Bold)
      Text('趋势用来调整节奏，不用单次结果评价自己。').fontColor('#B7C9C3')
      Row({ space: 8 }) {
        this.metric('今日复习', this.summary.reviewsToday, '次')
        this.metric('近 7 日正确', this.summary.accuracyPercent, '%')
        this.metric('连续学习', this.summary.streakDays, '天')
      }.width('100%')
      this.chartPanel('复习活跃度', '过去 7 天完成的复习次数', this.summary.reviewDays, false, '#78E8C1')
      this.chartPanel('未来负载', '今天起 7 天的到期内容', this.summary.dueDays, true, '#C9B8FF')
      Column({ space: 10 }) {
        Text('知识库进度').fontSize(20).fontWeight(FontWeight.Medium)
        Text(`${this.summary.masteredItems} / ${this.summary.activeItems} 条熟练内容 · 累计 ${this.summary.totalReviews} 次复习`)
          .fontColor('#B7C9C3')
        Progress({ value: this.summary.masteredItems, total: Math.max(1, this.summary.activeItems), type: ProgressType.Linear })
          .color('#78E8C1').backgroundColor('#243A34').height(8).width('100%')
        Text('稳定度达到 14 天且至少复习 3 次，记为熟练。').fontSize(13).fontColor('#B7C9C3')
      }.padding(18).width('100%').backgroundColor('#101C19').borderRadius(22)
        .border({ width: 1, color: '#345148' })
      Column({ space: 10 }) {
        Text('学科分布').fontSize(20).fontWeight(FontWeight.Medium)
        if (this.summary.subjects.length === 0) Text('添加内容后，这里会出现学科分布。').fontColor('#B7C9C3')
        ForEach(this.summary.subjects, (subject: SubjectInsight) => {
          Row() { Text(this.subjectLabel(subject.subject)).layoutWeight(1)
            Text(`${subject.mastered}/${subject.total}`).fontColor('#B7C9C3') }
          Progress({ value: subject.mastered, total: Math.max(1, subject.total), type: ProgressType.Linear })
            .color(subject.subject === 'math' ? '#C9B8FF' : '#78E8C1')
            .backgroundColor('#243A34').height(6).width('100%')
        }, (subject) => subject.subject)
      }.padding(18).width('100%').backgroundColor('#101C19').borderRadius(22)
        .border({ width: 1, color: '#345148' })
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder metric(label: string, value: number, unit: string): void {
    Column({ space: 3 }) { Row() { Text(`${value}`).fontSize(26).fontWeight(FontWeight.Bold).fontColor('#78E8C1')
        Text(unit).fontSize(13).fontColor('#B7C9C3').margin({ left: 3, top: 10 }) }
      Text(label).fontSize(12).fontColor('#B7C9C3')
    }.layoutWeight(1).padding(14).backgroundColor('#101C19').borderRadius(20)
      .border({ width: 1, color: '#345148' })
  }
  @Builder chartPanel(title: string, subtitle: string, values: number[], future: boolean, color: string): void {
    Column({ space: 10 }) { Text(title).fontSize(20).fontWeight(FontWeight.Medium)
      Text(subtitle).fontSize(13).fontColor('#B7C9C3')
      Row({ space: 7 }) { ForEach(values, (value: number, index: number) => {
        Column({ space: 5 }) { Text(`${value}`).fontSize(11).fontColor('#B7C9C3')
          Blank().height(12 + 84 * value / this.maxValue(values)).width('72%').backgroundColor(color).borderRadius(7)
            .animation({ duration: 420, curve: Curve.EaseOut })
          Text(this.weekday(future ? index : index - 6)).fontSize(11).fontColor('#B7C9C3')
        }.layoutWeight(1).height(132).justifyContent(FlexAlign.End)
      }) }.width('100%')
    }.padding(18).width('100%').backgroundColor('#101C19').borderRadius(22)
      .border({ width: 1, color: '#345148' })
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
      TextInput({ text: this.search, placeholder: '搜索题干或答案' }).height(48)
        .onChange(value => { this.search = value; this.refresh(); })
      ForEach(this.items, (item: StudyItem) => {
        Column({ space: 6 }) { Text(item.prompt.length > 0 ? item.prompt : '图片题面').fontSize(17).maxLines(3)
          Text(item.answer).fontSize(14).fontColor('#B7C9C3').maxLines(2)
          Text(item.dueAt <= Math.floor(Date.now() / 1000) ? '待复习' : `已复习 ${item.repetitions} 次`)
            .fontSize(12).fontColor('#78E8C1')
        }.padding(14).width('100%').backgroundColor('#101C19').borderRadius(18)
          .border({ width: 1, color: '#345148' })
      }, (item: StudyItem) => item.id)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder addPage(): void {
    Column({ space: 12 }) { Text('新建 408 记忆卡').fontSize(20).fontWeight(FontWeight.Medium)
      TextArea({ text: this.prompt, placeholder: '题干' }).height(120).onChange(value => this.prompt = value)
      TextArea({ text: this.answer, placeholder: '答案' }).height(160).onChange(value => this.answer = value)
      Button('保存记忆卡').height(50).width('100%').onClick(() => this.createCard())
      Text('数学题可由已同步设备带入；本页在手机、平板和 2in1 上保持本地可用。')
        .fontSize(14).fontColor('#B7C9C3')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  private async createCard(): Promise<void> {
    try { await this.database?.createMemoryCard(this.prompt, this.answer); this.prompt = ''; this.answer = '';
      this.message = '记忆卡已保存'; this.destination = Destination.Today; await this.refresh(); await this.runSync();
    } catch (error) { this.message = (error as Error).message; }
  }
  private async startReview(): Promise<void> {
    this.current = await this.database?.nextForReview(); this.answerRevealed = false; this.strokes = [];
    this.reviewStartedAt = Math.floor(Date.now() / 1000);
    if (!this.current) this.message = '当前没有待复习内容';
  }
  @Builder reviewPage(): void {
    if (!this.current) { Column({ space: 12 }) { Text('当前没有待复习内容').fontSize(18)
      Button('返回今日').onClick(() => this.destination = Destination.Today) } }
    else {
      Column({ space: 14 }) { Text(this.current.kind === 'math_problem' ? '数学 · 专注重做' : '408 · 主动回忆')
          .fontColor('#78E8C1').fontWeight(FontWeight.Medium)
        Column() { Text(this.current.prompt.length > 0 ? this.current.prompt : '图片题面').fontSize(24) }
          .padding(20).width('100%').backgroundColor('#101C19').borderRadius(22)
            .border({ width: 1, color: '#345148' })
        if (this.current.kind === 'math_problem' && !this.answerRevealed) this.inkPad()
        if (!this.answerRevealed) Button('显示答案').height(52).width('100%').onClick(() => this.answerRevealed = true)
        else { Column({ space: 10 }) { Text('参考答案').fontSize(18).fontWeight(FontWeight.Medium)
            Text(this.current.answer.length > 0 ? this.current.answer : '尚未填写参考答案') }
            .padding(18).width('100%').backgroundColor('#1C2B27').borderRadius(18)
          Row({ space: 8 }) { ForEach(this.current.kind === 'math_problem' ? ['不会', '做错', '勉强', '熟练'] :
            ['忘记', '困难', '正确', '轻松'], (label: string, index: number) => {
              Button(label).height(48).layoutWeight(1).onClick(() => this.score(index + 1))
            }) }.width('100%') }
      }.width('100%').alignItems(HorizontalAlign.Start)
    }
  }
  private async score(value: number): Promise<void> {
    if (!this.current || !this.database) return;
    try { const now = Math.floor(Date.now() / 1000); await this.database.review(this.current,
      this.current.kind === 'math_problem' ? value - 1 : value, now - this.reviewStartedAt);
      this.message = '复习已保存'; await this.refresh(); await this.startReview(); await this.runSync();
    } catch (error) { this.message = (error as Error).message; }
  }
  @Builder inkPad(): void {
    Column({ space: 8 }) { Row({ space: 8 }) { Button('笔').onClick(() => this.inkTool = 'pen')
        Button('荧光').onClick(() => this.inkTool = 'highlighter')
        Button('橡皮').onClick(() => this.inkTool = 'eraser')
        Button('撤销').onClick(() => { this.strokes = this.strokes.slice(0, -1); this.renderInk(); })
        Button('清空').onClick(() => { this.strokes = []; this.renderInk(); }) }
      Row({ space: 8 }) { ForEach(['#E5F0EC', '#FF8C83', '#78E8C1', '#FFC66C'], (color: string) => {
        Button().width(44).height(44).backgroundColor(color).onClick(() => this.inkColor = color)
      }) }
      Canvas(this.canvas).width('100%').height(420).backgroundColor('#101C19').borderRadius(18)
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
      this.canvas.strokeStyle = stroke.tool === 'eraser' ? '#101C19' : stroke.color;
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
      Divider(); Text('本地数据可在未登录和断网时继续使用。').fontColor('#B7C9C3')
    }.padding(18).width('100%').backgroundColor('#101C19').borderRadius(22)
      .border({ width: 1, color: '#345148' }).alignItems(HorizontalAlign.Start)
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
      .width('100%').height('100%').backgroundColor('#07110F')
      .onAreaChange((_oldValue: Area, newValue: Area) => this.widthVp = Number(newValue.width))
  }
}
