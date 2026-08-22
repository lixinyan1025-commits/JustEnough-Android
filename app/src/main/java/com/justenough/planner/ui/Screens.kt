package com.justenough.planner.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.net.Uri
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import com.justenough.planner.MainActivity
import com.justenough.planner.appContainer
import com.justenough.planner.data.*
import com.justenough.planner.diagnostics.DeviceGuide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun TodayScreen(vm: AppViewModel, padding: PaddingValues) {
    val snapshot by vm.today.collectAsState()
    val context = LocalContext.current
    var reviewDateFor by remember { mutableStateOf<PlanDetails?>(null) }
    var snoozePlan by remember { mutableStateOf<PlanDetails?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LifecycleResumeEffect(Unit) {
        vm.refreshToday()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(1000) }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val date = now.toLocalDate()
            val time = now.toLocalTime()
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
            Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日 $weekday", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%02d:%02d:%02d".format(time.hour, time.minute, time.second), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            val minute = time.toSecondOfDay() / 60
            snapshot?.todayBlocks?.firstOrNull { it.block.startMinute > minute }?.let { next ->
                val diff = next.block.startMinute - minute
                val names = next.plans.take(2).joinToString("、") { it.name }.ifBlank { next.block.name }
                Text("下一个：$names · ${clock(next.block.startMinute)} 开始 · 还有 $diff 分钟", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Fulfillment(snapshot?.fulfillmentTotal ?: 0) }
        snapshot?.activeRun?.let { run ->
            item {
                Section("正在进行")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(run.name, fontWeight = FontWeight.Bold)
                        Text(run.minimumGoal)
                        Row {
                            TextButton({ vm.toggleRun(run.runId) }) { Text(if (run.status == RunStatuses.PAUSED) "继续" else "暂停") }
                            TextButton({ vm.extendRun(run.runId) }) { Text("延长10分钟") }
                            Button({ context.startActivity(Intent(context, TaskFinishActivity::class.java).putExtra("run_id", run.runId).putExtra("finish_mode", "COMPLETE")) }) { Text("完成") }
                        }
                        Row {
                            TextButton({ context.startActivity(Intent(context, TaskFinishActivity::class.java).putExtra("run_id", run.runId).putExtra("finish_mode", "ENOUGH")) }) { Text("够了") }
                            TextButton({ context.startActivity(Intent(context, TaskFinishActivity::class.java).putExtra("run_id", run.runId).putExtra("finish_mode", "ABANDON")) }) { Text("放弃", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        item {
            Section("今日安排")
        }
        items(snapshot?.todayBlocks.orEmpty(), key = { "block-${it.block.id}" }) { block ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("${clock(block.block.startMinute)}–${clock(block.block.endMinute)}  ${block.block.name}", fontWeight = FontWeight.SemiBold)
                block.plans.forEach { plan ->
                    val complete = plan.id in (snapshot?.completedPlanIds ?: emptySet())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "   ${if (complete) "✓" else "○"} ${plan.name}",
                            Modifier.weight(1f).clickable(enabled=!complete) { openPlan(context, plan.id) }.padding(vertical = 8.dp),
                        )
                        if (!complete && snapshot?.activeRun?.planId != plan.id) TextButton({ snoozePlan = plan }) { Text("稍后", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (snapshot?.todayBlocks.isNullOrEmpty()) {
            item { Text("今天还没有安排。请到“计划 → 计划安排”添加。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!snapshot?.reviewCandidates.isNullOrEmpty()) {
            item { Section("晚间复盘") }
            items(snapshot?.reviewCandidates.orEmpty(), key = { "review-${it.id}" }) { plan ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(plan.name, fontWeight = FontWeight.SemiBold)
                        Text("今天未完成，选择它接下来去哪里。", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton({ vm.returnToPool(plan.id) }) { Text("放回候选池") }
                            TextButton({ vm.scheduleFor(plan.id, LocalDate.now().plusDays(1)) }) { Text("明天") }
                            TextButton({ reviewDateFor = plan }) { Text("其他日期") }
                        }
                        TextButton({ vm.removeFromReview(plan.id) }) { Text("删除（归档）") }
                    }
                }
            }
        }
    }
    snoozePlan?.let { plan -> SnoozePlanDialog(plan, { snoozePlan = null }) { minutes -> vm.scheduleSnooze(plan.id, minutes); snoozePlan = null } }
    reviewDateFor?.let { plan ->
        val today = LocalDate.now()
        LaunchedEffect(plan.id) {
            android.app.DatePickerDialog(context, { _, y, m, d ->
                vm.scheduleFor(plan.id, LocalDate.of(y, m + 1, d)); reviewDateFor = null
            }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
                setOnCancelListener { reviewDateFor = null }
                setOnDismissListener { reviewDateFor = null }
            }.show()
        }
    }
}

@Composable
private fun Fulfillment(total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("今日充实度", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { index ->
                Surface(
                    color = if (index < total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(44.dp, 12.dp),
                ) {}
            }
        }
        if (total > 5) Text("溢出 +${total - 5}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TaskRow(plan: PlanDetails) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { openPlan(context, plan.id) }) {
        Column(Modifier.padding(16.dp)) {
            Text(plan.name, fontWeight = FontWeight.Bold)
            Text("${plan.minimumGoal} · ${plan.estimatedMinutes}分钟", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(vm: AppViewModel, padding: PaddingValues) {
    val state by vm.plannerState.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var newPlan by remember { mutableStateOf(false) }
    var newBlock by remember { mutableStateOf(false) }
    var newTodayBlock by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<TimeBlockEntity?>(null) }
    var editingPlan by remember { mutableStateOf<PlanEntity?>(null) }
    var newPlanQuadrant by remember { mutableStateOf<String?>(null) }
    var quadrantDetail by remember { mutableStateOf<String?>(null) }
    var deletingPlan by remember { mutableStateOf<PlanEntity?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var dragging by remember { mutableStateOf<PlanEntity?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    val quadrantBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val scheduleListState = rememberLazyListState()
    val visiblePlans = state.plans.filter { !it.archived }
    val unclassified = visiblePlans.filter { !PlanQuadrants.isValid(it.quadrant) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("我的计划", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("共 ${visiblePlans.size} 项计划", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton({ searchOpen = true }) { Icon(Icons.Outlined.Search, "搜索计划") }
            IconButton({ newPlan = true }) { Icon(Icons.Outlined.Add, "添加计划") }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("计划") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("计划安排") })
        }
        if (tab == 0) {
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuadrantPanel(PlanQuadrants.IMPORTANT_URGENT, visiblePlans, Modifier.weight(1f), quadrantBounds, dragging, dragPoint, { editingPlan = it }, { plan, point -> dragging=plan;dragPoint=point }, { plan, point -> dropPlan(vm, plan, point, quadrantBounds, visiblePlans);dragging=null }, { quadrantDetail = PlanQuadrants.IMPORTANT_URGENT })
                    QuadrantPanel(PlanQuadrants.IMPORTANT_NOT_URGENT, visiblePlans, Modifier.weight(1f), quadrantBounds, dragging, dragPoint, { editingPlan = it }, { plan, point -> dragging=plan;dragPoint=point }, { plan, point -> dropPlan(vm, plan, point, quadrantBounds, visiblePlans);dragging=null }, { quadrantDetail = PlanQuadrants.IMPORTANT_NOT_URGENT })
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuadrantPanel(PlanQuadrants.NOT_IMPORTANT_URGENT, visiblePlans, Modifier.weight(1f), quadrantBounds, dragging, dragPoint, { editingPlan = it }, { plan, point -> dragging=plan;dragPoint=point }, { plan, point -> dropPlan(vm, plan, point, quadrantBounds, visiblePlans);dragging=null }, { quadrantDetail = PlanQuadrants.NOT_IMPORTANT_URGENT })
                    QuadrantPanel(PlanQuadrants.NOT_IMPORTANT_NOT_URGENT, visiblePlans, Modifier.weight(1f), quadrantBounds, dragging, dragPoint, { editingPlan = it }, { plan, point -> dragging=plan;dragPoint=point }, { plan, point -> dropPlan(vm, plan, point, quadrantBounds, visiblePlans);dragging=null }, { quadrantDetail = PlanQuadrants.NOT_IMPORTANT_NOT_URGENT })
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), state=scheduleListState, contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ newTodayBlock = true }, Modifier.weight(1f)) { Text("安排") }
                        OutlinedButton({ newBlock = true }, Modifier.weight(1f)) { Text("添加重复安排") }
                    }
                    Text("在这里编辑今天页面的全部内容。每个时间段都可以自由选择多个计划。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.blocks, key = { it.id }) { block ->
                    Column(Modifier.fillMaxWidth().clickable { editingBlock = block }.padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${clock(block.startMinute)}–${clock(block.endMinute)}  ${block.name}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            IconButton({ vm.deleteBlock(block) }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(if (block.kind == PlanKinds.ANCHOR) "固定任务" else "自由选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.schedulePlans.filter { it.blockId == block.id }.mapNotNull { ref -> state.plans.find { it.id == ref.planId }?.name }.forEach { name ->
                            Text("   ○  $name", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (newPlan) PlanDialog({ newPlan = false; newPlanQuadrant = null }, presetQuadrant = newPlanQuadrant.orEmpty()) { name, goal, minutes, quadrant -> vm.addPlan(name, goal, minutes, quadrant); newPlan = false; newPlanQuadrant = null }
    editingPlan?.let { current -> PlanDialog(
        onDismiss={editingPlan=null},initial=current,onDelete={deletingPlan=current;editingPlan=null},
        onBind={context.startActivity(Intent(context,AppBindingActivity::class.java).putExtra("plan_id",current.id));editingPlan=null},
        onUnbind={vm.bindApp(current.id,null,null);editingPlan=null},onTest={vm.testOpenApp(current)},
    ) { name, goal, minutes, quadrant -> vm.updatePlan(current.copy(name = name, minimumGoal = goal, estimatedMinutes = minutes, quadrant=quadrant)); editingPlan = null } }
    deletingPlan?.let { current -> AlertDialog(onDismissRequest={deletingPlan=null},title={Text("永久删除？")},text={Text("将同时删除“${current.name}”的执行历史和充实度记录，无法恢复。一般建议使用归档。")},confirmButton={TextButton({vm.permanentlyDeletePlan(current.id);deletingPlan=null}){Text("永久删除")}},dismissButton={TextButton({deletingPlan=null}){Text("取消")}}) }
    if (newBlock) ScheduleDialog(state.plans.filter { !it.archived }, { newBlock = false }) { block, ids -> vm.addBlock(block, ids); newBlock = false }
    if (newTodayBlock) {
        val start=(java.time.LocalTime.now().hour+1).coerceAtMost(22)*60
        ScheduleDialog(state.plans.filter { !it.archived }, { newTodayBlock=false }, TimeBlockEntity(name="今天",kind=PlanKinds.CHOICE,startMinute=start,endMinute=(start+60).coerceAtMost(1439),dateEpochDay=LocalDate.now().toEpochDay()), todayMode=true) { block,ids ->
            vm.addBlock(block.copy(id=0,dateEpochDay=LocalDate.now().toEpochDay()),ids);newTodayBlock=false
        }
    }
    editingBlock?.let { current ->
        val selected = state.schedulePlans.filter { it.blockId == current.id }.map { it.planId }
        ScheduleDialog(state.plans.filter { !it.archived }, { editingBlock = null }, current, selected) { block, ids ->
            vm.updateBlock(block.copy(id = current.id), ids); editingBlock = null
        }
    }
    quadrantDetail?.let { q ->
        QuadrantDetailDialog(
            q,
            state.plans.filter { !it.archived && it.quadrant == q },
            { quadrantDetail = null },
            { editingPlan = it; quadrantDetail = null },
            { newPlanQuadrant = q; newPlan = true; quadrantDetail = null },
        )
    }
    if (unclassified.isNotEmpty()) ClassificationDialog(unclassified) { vm.classifyPlans(it) }
    if (searchOpen) AlertDialog(onDismissRequest={searchOpen=false;searchText=""},title={Text("搜索计划")},text={Column{OutlinedTextField(searchText,{searchText=it},Modifier.fillMaxWidth(),singleLine=true);LazyColumn(Modifier.heightIn(max=360.dp)){items(visiblePlans.filter{searchText.isBlank()||it.name.contains(searchText,true)||it.minimumGoal.contains(searchText,true)},key={it.id}){plan->Text("${quadrantLabel(plan.quadrant)} · ${plan.name}",Modifier.fillMaxWidth().clickable{editingPlan=plan;searchOpen=false}.padding(vertical=12.dp))}}}},confirmButton={},dismissButton={TextButton({searchOpen=false;searchText=""}){Text("关闭")}})
}

@Composable
private fun QuadrantPanel(quadrant:String, plans:List<PlanEntity>, modifier:Modifier, bounds:MutableMap<String,androidx.compose.ui.geometry.Rect>, dragging:PlanEntity?, dragPoint:Offset, onClick:(PlanEntity)->Unit, onDrag:(PlanEntity,Offset)->Unit, onDrop:(PlanEntity,Offset)->Unit, onOpen:()->Unit){
    val values=plans.filter{it.quadrant==quadrant}.sortedBy{it.matrixOrder};val listState=rememberLazyListState();val colors=quadrantColors(quadrant);val highlighted=dragging!=null&&bounds[quadrant]?.contains(dragPoint)==true
    Surface(modifier.onGloballyPositioned{bounds[quadrant]=it.boundsInWindow()},color=if(highlighted)colors.first.copy(alpha=.82f)else colors.first,shape=MaterialTheme.shapes.large,border=if(highlighted)androidx.compose.foundation.BorderStroke(2.dp,colors.second)else null){Column(Modifier.padding(10.dp)){Row(Modifier.fillMaxWidth().clickable{onOpen()},horizontalArrangement=Arrangement.SpaceBetween){Text(quadrantLabel(quadrant),fontWeight=FontWeight.Bold,color=colors.second,style=MaterialTheme.typography.labelLarge);Text("${values.size} · ＋",color=colors.second,style=MaterialTheme.typography.labelMedium)};Box(Modifier.weight(1f).fillMaxWidth().clickable(enabled=dragging==null){onOpen()}){LazyColumn(state=listState,verticalArrangement=Arrangement.spacedBy(3.dp),contentPadding=PaddingValues(vertical=6.dp)){items(values,key={it.id}){plan->var origin by remember{mutableStateOf(Offset.Zero)};Surface(color=Color.White.copy(alpha=.62f),shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().onGloballyPositioned{origin=it.boundsInWindow().center}.graphicsLayer{alpha=if(dragging?.id==plan.id).45f else 1f}.pointerInput(plan.id){detectDragGesturesAfterLongPress(onDragStart={onDrag(plan,origin)},onDrag={change,amount->change.consume();origin+=amount;onDrag(plan,origin)},onDragEnd={onDrop(plan,origin)},onDragCancel={onDrop(plan,origin)})}.clickable{onClick(plan)}){Text((if(plan.appPackage!=null)"◈ " else "")+plan.name,Modifier.padding(horizontal=8.dp,vertical=7.dp),maxLines=1,style=MaterialTheme.typography.bodySmall)}}};if(listState.canScrollBackward||listState.canScrollForward)Box(Modifier.align(androidx.compose.ui.Alignment.CenterEnd).width(3.dp).fillMaxHeight(.55f).padding(vertical=4.dp).graphicsLayer{alpha=.45f}.then(Modifier)) { Surface(Modifier.fillMaxSize(),color=colors.second,shape=MaterialTheme.shapes.small){} }};if(values.isEmpty())Box(Modifier.fillMaxSize().clickable{onOpen()},contentAlignment=androidx.compose.ui.Alignment.Center){Text("点此添加计划",color=colors.second,style=MaterialTheme.typography.bodySmall)}}}}
private fun dropPlan(vm:AppViewModel,plan:PlanEntity,point:Offset,bounds:Map<String,androidx.compose.ui.geometry.Rect>,plans:List<PlanEntity>){val target=bounds.entries.firstOrNull{it.value.contains(point)}?:return;val targetPlans=plans.filter{it.quadrant==target.key&&it.id!=plan.id}.sortedBy{it.matrixOrder};val rect=target.value;val relative=((point.y-rect.top)/rect.height).coerceIn(0f,1f);val before=targetPlans.getOrNull((relative*targetPlans.size).toInt())?.id;vm.movePlan(plan.id,target.key,before)}
private fun quadrantLabel(value:String)=when(value){PlanQuadrants.IMPORTANT_URGENT->"重要且紧急";PlanQuadrants.IMPORTANT_NOT_URGENT->"重要不紧急";PlanQuadrants.NOT_IMPORTANT_URGENT->"不重要但紧急";PlanQuadrants.NOT_IMPORTANT_NOT_URGENT->"不重要也不紧急";else->"未分类"}
private fun quadrantColors(value:String)=when(value){PlanQuadrants.IMPORTANT_URGENT->Color(0xFFF4DFDB) to Color(0xFFA6534B);PlanQuadrants.IMPORTANT_NOT_URGENT->Color(0xFFDFEBE5) to Color(0xFF315D51);PlanQuadrants.NOT_IMPORTANT_URGENT->Color(0xFFF3E8CC) to Color(0xFF806A2D);else->Color(0xFFE9E5EC) to Color(0xFF6C6474)}

@Composable private fun QuadrantDetailDialog(quadrant:String, plans:List<PlanEntity>, onDismiss:()->Unit, onEdit:(PlanEntity)->Unit, onAdd:()->Unit){
    val colors=quadrantColors(quadrant)
    AlertDialog(onDismissRequest=onDismiss,title={Text(quadrantLabel(quadrant),color=colors.second)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("共 ${plans.size} 项计划",color=colors.second,style=MaterialTheme.typography.labelLarge);TextButton(onAdd){Text("添加计划")}}
        if(plans.isEmpty()){Text("这个象限还没有计划。点击右上角“添加计划”开始。",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
        else LazyColumn(Modifier.heightIn(max=420.dp)){items(plans,key={it.id}){plan->Row(Modifier.fillMaxWidth().clickable{onEdit(plan)}.padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text((if(plan.appPackage!=null)"◈ " else "")+plan.name,fontWeight=FontWeight.SemiBold);Text("${plan.minimumGoal} · ${plan.estimatedMinutes}分钟",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)};Text("编辑",color=colors.second,style=MaterialTheme.typography.labelMedium)}}}
    }},confirmButton={},dismissButton={TextButton(onDismiss){Text("关闭")}})
}

@Composable private fun QuadrantSelector(selected:String,onSelect:(String)->Unit){Column{PlanQuadrants.ALL.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{q->FilterChip(selected==q,{onSelect(q)},{Text(quadrantLabel(q),style=MaterialTheme.typography.labelSmall)})}}}}}
@Composable private fun ClassificationDialog(plans:List<PlanEntity>,onSave:(Map<Long,String>)->Unit){val choices=remember(plans){mutableStateMapOf<Long,String>()};AlertDialog(onDismissRequest={},title={Text("先为计划选择四象限")},text={Column{Text("新版计划页只有四个象限。全部分类后才能继续使用计划页。",style=MaterialTheme.typography.bodySmall);LazyColumn(Modifier.heightIn(max=430.dp)){items(plans,key={it.id}){plan->Column(Modifier.padding(vertical=8.dp)){Text(plan.name,fontWeight=FontWeight.SemiBold);QuadrantSelector(choices[plan.id].orEmpty()){choices[plan.id]=it}}}}}},confirmButton={Button({onSave(choices.toMap())},enabled=choices.size==plans.size){Text("完成分类")}})}

@Composable
fun AiAnalysisScreen(vm: AppViewModel, padding: PaddingValues) {
    val state by vm.plannerState.collectAsState()
    val proposal by vm.proposal.collectAsState()
    var input by remember { mutableStateOf("") }
    var pendingManualAction by remember { mutableStateOf<String?>(null) }
    val todayEpoch = LocalDate.now().toEpochDay()
    val limitReached = state.settings.analysisEpochDay == todayEpoch && state.settings.analysisCount >= state.settings.autoAnalysisLimit
    val selectedChanges = remember(proposal) { mutableStateListOf<Int>().apply { proposal?.let {
        addAll(it.plans.indices)
        addAll(it.planChanges.indices.map { i -> 10_000 + i })
        addAll(it.schedules.indices.map { i -> 20_000 + i })
        addAll(it.scheduleChanges.indices.map { i -> 30_000 + i })
    } } }
    Column(Modifier.fillMaxSize().padding(padding)) {
        state.aiMessages.lastOrNull { it.kind != "CHAT" }?.let { latest ->
            Card(Modifier.fillMaxWidth().padding(16.dp)) { Text(latest.content, Modifier.padding(16.dp)) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(state.feedback.isNotEmpty()) item {
                val cutoff=System.currentTimeMillis()-30L*24*60*60*1000
                val recent=state.feedback.filter{it.createdAt>=cutoff&&it.score!=null}
                if(recent.isNotEmpty()) OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("近30天真实感受",fontWeight=FontWeight.Bold);recent.groupBy{it.kind}.forEach{(kind,values)->val label=when(kind){FeedbackKinds.ANTICIPATED_DIFFICULTY->"预期难度";FeedbackKinds.FATIGUE->"实际疲劳";FeedbackKinds.EASE->"轻松程度";else->"完成后心情"};Text("$label：${"%.1f".format(values.mapNotNull{it.score}.average())}/5 · ${values.size}次",style=MaterialTheme.typography.bodySmall)}}}
            }
            if(state.aiMessages.isEmpty()) item {
                Text(if(state.settings.aiConnectionVerified) "可以直接聊计划，也可以让AI生成可勾选的调整建议。" else "请先在“设置 → AI接口”完成连接测试。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.aiMessages, key = { it.id }) { item ->
                Surface(
                    color = if (item.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(item.content, Modifier.padding(12.dp)) }
            }
            proposal?.let { value ->
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("AI调整建议", fontWeight = FontWeight.Bold)
                            Text(value.summary)
                            value.plans.forEachIndexed { index, item -> Row(Modifier.fillMaxWidth().clickable { if(index in selectedChanges) selectedChanges.remove(index) else selectedChanges.add(index) }) { Checkbox(index in selectedChanges, null); Text("新增 ${item.name}", Modifier.padding(top=12.dp)) } }
                            value.planChanges.forEachIndexed { index, item -> val key=10_000+index; Row(Modifier.fillMaxWidth().clickable { if(key in selectedChanges) selectedChanges.remove(key) else selectedChanges.add(key) }) { Checkbox(key in selectedChanges, null); Text("调整 ${item.existingName}", Modifier.padding(top=12.dp)) } }
                            value.schedules.forEachIndexed { index, item -> val key=20_000+index; Row(Modifier.fillMaxWidth().clickable { if(key in selectedChanges) selectedChanges.remove(key) else selectedChanges.add(key) }) { Checkbox(key in selectedChanges, null); Text("新增安排 ${item.name}", Modifier.padding(top=12.dp)) } }
                            value.scheduleChanges.forEachIndexed { index, item -> val key=30_000+index; Row(Modifier.fillMaxWidth().clickable { if(key in selectedChanges) selectedChanges.remove(key) else selectedChanges.add(key) }) { Checkbox(key in selectedChanges, null); Text("调整安排 ${item.existingName}", Modifier.padding(top=12.dp)) } }
                            value.warnings.forEach { warning -> Text("注意：$warning", color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.bodySmall) }
                            Row { Button({ vm.applySelectedProposal(selectedChanges.toSet()) }) { Text("确认应用") }; TextButton(vm::dismissProposal) { Text("取消") } }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), placeholder = { Text("聊聊计划，或请AI分析") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ if (limitReached) pendingManualAction="ANALYZE" else { vm.analyze(input); input = "" } },enabled=state.settings.aiConnectionVerified&&input.isNotBlank()) { Text("分析调整") }
                Button({ if (limitReached) pendingManualAction="CHAT" else { vm.sendAiMessage(input); input = "" } }, Modifier.padding(start = 8.dp),enabled=state.settings.aiConnectionVerified&&input.isNotBlank()) { Text("发送") }
            }
        }
    }
    if (pendingManualAction != null) AlertDialog(
        onDismissRequest={pendingManualAction=null},
        title={Text("今日自动分析额度已用完")},
        text={Text("继续会进行一次手动请求；待分析的计划变化仍会保留到明天合并处理。")},
        confirmButton={TextButton({val action=pendingManualAction;pendingManualAction=null;if(action=="ANALYZE")vm.analyze(input)else vm.sendAiMessage(input);input=""}){Text("继续")}},
        dismissButton={TextButton({pendingManualAction=null}){Text("取消")}},
    )
}

@Composable
fun SettingsScreen(vm: AppViewModel, padding: PaddingValues) {
    val state by vm.plannerState.collectAsState()
    val keyPresent by vm.apiKeyPresent.collectAsState()
    val expiredCount by vm.expiredAiMessageCount.collectAsState()
    val backupPreview by vm.backupPreview.collectAsState()
    val context = LocalContext.current
    var base by remember(state.settings.aiBaseUrl) { mutableStateOf(state.settings.aiBaseUrl) }
    var model by remember(state.settings.aiModel) { mutableStateOf(state.settings.aiModel) }
    var key by remember { mutableStateOf("") }
    var fallbackKey by remember { mutableStateOf("") }
    var fallbackBase by remember(state.settings.fallbackBaseUrl) { mutableStateOf(state.settings.fallbackBaseUrl) }
    var fallbackModel by remember(state.settings.fallbackModel) { mutableStateOf(state.settings.fallbackModel) }
    var backupUri by remember { mutableStateOf<Uri?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupMode by remember { mutableStateOf("") }
    var showPetConsent by remember { mutableStateOf(false) }
    var previewingSound by remember { mutableStateOf(false) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if(uri!=null){backupUri=uri;backupMode="EXPORT"} }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri!=null){backupUri=uri;backupMode="INSPECT"} }
    val exportAiHistory = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null)vm.exportExpiredAiHistory(uri) }
    val pickRingtone = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.updateSettings { it.copy(taskRingtone = "CUSTOM", customRingtoneUri = uri.toString()) }
        }
    }
    DisposableEffect(Unit) { onDispose { RingtonePreview.stop() } }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (expiredCount > 0) item { Card { Column(Modifier.padding(16.dp)) { Text("有 $expiredCount 条AI记录已满90天",fontWeight=FontWeight.Bold);Text("你可以导出后删除、直接删除、延长90天，或暂不处理。");Row{TextButton({exportAiHistory.launch("刚刚好-AI历史-${java.time.LocalDate.now()}.json")}){Text("导出后删除")};TextButton(vm::deleteExpiredAiHistory){Text("删除")};TextButton(vm::extendAiHistory){Text("延长")}};TextButton(vm::remindHistoryLater){Text("7天后提醒")}} } }
        item { Section("AI接口"); Text("状态：${statusText(state.settings.aiConnectionStatus)}"); Text(state.settings.aiConnectionDetail, color = if (state.settings.aiConnectionStatus == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Text("主要接口",fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(state.settings.aiProvider=="DEEPSEEK",{
                    base=DeepSeekConfig.BASE_URL;model=DeepSeekConfig.FLASH
                    vm.updateSettings{it.copy(aiProvider="DEEPSEEK",aiBaseUrl=base,aiModel=model,aiConnectionVerified=false,aiConnectionStatus="UNCONFIGURED",aiConnectionDetail="配置已变化，请重新测试")}
                },{Text("DeepSeek官方")})
                FilterChip(state.settings.aiProvider=="CUSTOM",{base=state.settings.aiBaseUrl.takeUnless{it==DeepSeekConfig.BASE_URL}.orEmpty();model="";vm.updateSettings{it.copy(aiProvider="CUSTOM",aiBaseUrl=base,aiModel=model,aiConnectionVerified=false,aiConnectionStatus="UNCONFIGURED",aiConnectionDetail="配置已变化，请重新测试")}},{Text("自定义兼容接口")})
            }
            if(state.settings.aiProvider=="DEEPSEEK"){
                Text("官方接口 · ${DeepSeekConfig.BASE_URL}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("选择模型",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(DeepSeekConfig.FLASH to "V4 Flash",DeepSeekConfig.PRO to "V4 Pro").forEach{(id,label)->FilterChip(model==id,{model=id;vm.updateSettings{it.copy(aiModel=id,aiConnectionVerified=false,aiConnectionStatus="UNCONFIGURED",aiConnectionDetail="模型已变化，请重新测试")}},{Text(label)})}}
            }else{
                OutlinedTextField(base, { base = it }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(key, { key = it }, label = { Text(if (keyPresent) "主要Key（已保存，留空不改）" else "主要API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Row { Checkbox(state.settings.aiConsent, { checked -> vm.updateSettings { it.copy(aiConsent = checked) } }); Text("允许发送计划与近30天聚合摘要", Modifier.padding(top = 12.dp)) }
            if(!state.settings.aiConsent) Text("勾选授权后才能测试；不会读取其他应用、文件或聊天。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ vm.saveAndTest(state.settings.aiProvider, base, model, key, state.settings.fallbackEnabled, fallbackBase, fallbackModel, fallbackKey) }, Modifier.fillMaxWidth(),enabled=state.settings.aiConsent) { Text("保存并测试") }
        }
        item {
            Row { Switch(state.settings.fallbackEnabled, { enabled -> vm.updateSettings { it.copy(fallbackEnabled = enabled) } }); Text("启用备用DeepSeek", Modifier.padding(12.dp)) }
            if (state.settings.fallbackEnabled) {
                Text("官方接口 · ${DeepSeekConfig.BASE_URL}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(DeepSeekConfig.FLASH to "V4 Flash",DeepSeekConfig.PRO to "V4 Pro").forEach{(id,label)->FilterChip(fallbackModel==id,{fallbackModel=id;vm.updateSettings{it.copy(fallbackModel=id,aiConnectionVerified=false,aiConnectionStatus="UNCONFIGURED",aiConnectionDetail="备用模型已变化，请重新测试")}},{Text(label)})}}
                OutlinedTextField(fallbackKey, { fallbackKey = it }, label = { Text("备用官方API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            }
        }
        item {
            Section("小满桌宠")
            if (!state.settings.aiConnectionVerified) Text("API首次测试成功后才可开启") else {
                Row { Switch(state.settings.petVisibility==PetVisibility.VISIBLE, { enabled ->
                    if (enabled && !state.settings.petPromptDismissed) showPetConsent=true else {
                        if (enabled && !Settings.canDrawOverlays(context)) context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
                        if(enabled)vm.setPetVisibility(PetVisibility.VISIBLE)else vm.setPetVisibility(PetVisibility.HIDDEN)
                    }
                }); Text("显示小满", Modifier.padding(12.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(48,64,80).forEach { size -> FilterChip(selected = state.settings.petSize == size, onClick = { vm.updateSettings { it.copy(petSize = size) }; if(state.settings.petVisibility==PetVisibility.VISIBLE)(context as? MainActivity)?.restartAiPet() }, label = { Text("${size}dp") }) } }
                Row { Checkbox(state.settings.petLocked, { locked -> vm.updateSettings { it.copy(petLocked = locked) } }); Text("锁定位置", Modifier.padding(top = 12.dp)) }
                Row { Checkbox(state.settings.completionVibration, { enabled -> vm.updateSettings { it.copy(completionVibration = enabled) } }); Text("任务完成时轻微振动", Modifier.padding(top = 12.dp)) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("小满提示管理", fontWeight = FontWeight.Bold)
                Row { Switch(state.settings.petActiveMessages, { enabled -> vm.updateSettings { it.copy(petActiveMessages = enabled) } }); Text("主动提示（倒计时、可做任务等）", Modifier.padding(top = 12.dp)) }
                Row { Switch(state.settings.petAskQuestions, { enabled -> vm.updateSettings { it.copy(petAskQuestions = enabled) } }); Text("主动提问（疲劳、轻松、心情）", Modifier.padding(top = 12.dp)) }
                Text("每日提问上限：${state.settings.petQuestionLimit}次", style = MaterialTheme.typography.bodySmall)
                Slider(state.settings.petQuestionLimit.toFloat(), { value -> vm.updateSettings { it.copy(petQuestionLimit = value.toInt()) } }, valueRange = 0f..20f, steps = 19)
                Text("安静时段：${clock(state.settings.petQuietStartMinute)}–${clock(state.settings.petQuietEndMinute)}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(state.settings.petQuietStartMinute == 1350 && state.settings.petQuietEndMinute == 420, { vm.updateSettings { it.copy(petQuietStartMinute = 1350, petQuietEndMinute = 420) } }, { Text("22:30–07:00") }); FilterChip(state.settings.petQuietStartMinute == 1380 && state.settings.petQuietEndMinute == 480, { vm.updateSettings { it.copy(petQuietStartMinute = 1380, petQuietEndMinute = 480) } }, { Text("23:00–08:00") }) }
                Text("气泡显示时长", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5 to "5秒", 8 to "8秒", 15 to "15秒", 30 to "30秒").forEach { (value, label) -> FilterChip(state.settings.petBubbleSeconds == value, { vm.updateSettings { it.copy(petBubbleSeconds = value) } }, { Text(label) }) } }
                Row { Switch(state.settings.petCompactChatEnabled, { enabled -> vm.updateSettings { it.copy(petCompactChatEnabled = enabled) } }); Text("桌面小窗聊天", Modifier.padding(top = 12.dp)) }
                Row { Switch(state.settings.petSoundEnabled, { enabled -> vm.updateSettings { it.copy(petSoundEnabled = enabled) } }); Text("主动消息提示音", Modifier.padding(top = 12.dp)) }
                if(state.settings.petVisibility==PetVisibility.VISIBLE)OutlinedButton({vm.setPetVisibility(PetVisibility.HIDDEN)},Modifier.fillMaxWidth()){Text("暂时隐藏小满")}
                if(state.settings.petVisibility!=PetVisibility.DISABLED)OutlinedButton({vm.setPetVisibility(PetVisibility.DISABLED)},Modifier.fillMaxWidth()){Text("彻底关闭小满")}
            }
        }
        item {
            Section("桌面与权限")
            Text("时间基准：手机系统时间与时区 · ${java.time.ZoneId.systemDefault().id}",style=MaterialTheme.typography.bodySmall)
            Text("手动改时间、切换时区或重启后，会自动重建未来提醒。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val activity=context as? MainActivity
            val notificationOk=activity?.notificationsAllowed()==true
            val alarmOk=activity?.exactAlarmsAllowed()==true
            Text("通知：${if(notificationOk)"已开启" else "未开启"}　准点提醒：${if(alarmOk)"已开启" else "未开启"}",color=if(notificationOk&&alarmOk)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(!notificationOk)Button({activity?.requestNotificationPermission()}){Text("开启通知")};if(!alarmOk)Button({activity?.openExactAlarmSettings()}){Text("开启准点提醒")}}
            val smallCount=activity?.widgetCount()?:0;val largeCount=activity?.largeWidgetCount()?:0
            Button({ activity?.requestLargeWidgetPin() },enabled=largeCount==0,modifier=Modifier.fillMaxWidth()) { Text(if(largeCount>0)"桌面计划组件已添加" else "添加桌面计划组件") }
            if(smallCount>0) Text("检测到旧版小组件 $smallCount 个。新版只保留一种“桌面计划组件”；请在桌面长按旧组件并移除。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(smallCount+largeCount>1) Text("桌面已有 ${smallCount+largeCount} 个组件。Android不允许应用代替你删除；请在桌面长按多余组件，选择“移除”。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            TextButton({ context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("悬浮窗权限") }; TextButton(vm::scheduleTest) { Text("测试提醒") }
        }
        item {
            Text("组件文字",fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("SYSTEM" to "跟随系统","LIGHT" to "浅色文字","DARK" to "深色文字").forEach{(mode,label)->FilterChip(state.settings.widgetTextMode==mode,{vm.updateSettings{it.copy(widgetTextMode=mode)}},{Text(label)})}}
            Text("字号 ${"%.0f".format(state.settings.widgetFontScale*100)}%",style=MaterialTheme.typography.bodySmall)
            Slider(state.settings.widgetFontScale,{value->vm.updateSettings{it.copy(widgetFontScale=value)}},valueRange=.85f..1.3f)
            Text("文字对比度 ${"%.0f".format(state.settings.widgetContrast*100)}%",style=MaterialTheme.typography.bodySmall)
            Slider(state.settings.widgetContrast,{value->vm.updateSettings{it.copy(widgetContrast=value)}},valueRange=.55f..1f)
            Row{Switch(state.settings.subtleBacking,{enabled->vm.updateSettings{it.copy(subtleBacking=enabled)}});Text("复杂壁纸下开启轻微局部衬底",Modifier.padding(12.dp))}
        }
        item {
            Section("设备适配与诊断")
            Text("当前：${android.os.Build.MANUFACTURER} · Android ${android.os.Build.VERSION.RELEASE}")
            Text(DeviceGuide.guide(state.settings.oemOverride))
            Column { listOf("AUTO","XIAOMI","HUAWEI","HONOR","OPPO","VIVO","SAMSUNG","ANDROID").chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { brand -> FilterChip(state.settings.oemOverride == brand, { vm.updateSettings { it.copy(oemOverride = brand) } }, { Text(brand) }) } } } }
            DeviceGuide.steps(state.settings.oemOverride).forEach { step ->
                OutlinedCard(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(step.title,fontWeight=FontWeight.SemiBold);Text(step.detail,style=MaterialTheme.typography.bodySmall) };TextButton({(context as? MainActivity)?.openDeviceGuideAction(step.action)}){Text("前往设置")} } }
            }
            Text("测试项", fontWeight = FontWeight.Bold)
            TextButton({ vm.scheduleTest() }) { Text("1. 提醒测试") }
            TextButton({ (context as? MainActivity)?.requestLargeWidgetPin() }) { Text("2. 组件添加与点击测试") }
            val sample = state.plans.firstOrNull { it.appPackage != null }
            TextButton({ if (sample != null) vm.testOpenApp(sample) }) { Text("3. 应用启动测试${if(sample==null)"（先绑定应用）" else ""}") }
            TextButton({ (context as? MainActivity)?.testOverlay() }) { Text("4. 悬浮窗测试") }
            if(state.diagnostics.isNotEmpty()) {
                Text("最近测试记录", fontWeight=FontWeight.Bold)
                state.diagnostics.take(6).forEach { event ->
                    Text("${event.type} · ${java.time.Instant.ofEpochMilli(event.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}\n${event.detail}", style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Section("声音与振动")
            Row { Switch(state.settings.feedbackSoundEnabled, { enabled -> vm.updateSettings { it.copy(feedbackSoundEnabled = enabled) } }); Text("声音总开关", Modifier.padding(top = 12.dp)) }
            Text("任务开始铃声", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("BUILTIN" to "内置清脆","SYSTEM_ALARM" to "系统闹钟","SYSTEM_NOTIFICATION" to "系统通知","SILENT" to "静音").forEach{(id,label)->FilterChip(state.settings.taskRingtone==id,{vm.updateSettings{it.copy(taskRingtone=id)}},{Text(label)})}}
Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton({pickRingtone.launch(arrayOf("audio/*","application/ogg","application/x-flac"))}){Text("选择本机音频")};TextButton({if(previewingSound){RingtonePreview.stop();previewingSound=false}else{RingtonePreview.play(context,state.settings.taskRingtone,state.settings.customRingtoneUri);previewingSound=true}}){Text(if(previewingSound)"试听关闭" else "试听")}}
            if(state.settings.taskRingtone=="CUSTOM")Text("当前：${state.settings.customRingtoneUri.takeLast(48)}",style=MaterialTheme.typography.bodySmall)
            Text("最长响铃时长", style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(30 to "30秒",60 to "1分钟",120 to "2分钟",300 to "5分钟",0 to "直到手动关闭").forEach{(value,label)->FilterChip(state.settings.ringtoneDurationSeconds==value,{vm.updateSettings{it.copy(ringtoneDurationSeconds=value)}},{Text(label)})}}
            Row { Switch(state.settings.ringtoneVibrate, { enabled -> vm.updateSettings { it.copy(ringtoneVibrate = enabled) } }); Text("响铃时振动", Modifier.padding(top = 12.dp)) }
            Row { Switch(state.settings.ringtoneVolumeRamp, { enabled -> vm.updateSettings { it.copy(ringtoneVolumeRamp = enabled) } }); Text("音量渐强", Modifier.padding(top = 12.dp)) }
            Text("任务说明浮层", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Row { Switch(state.settings.taskOverlayEnabled, { enabled -> vm.updateSettings { it.copy(taskOverlayEnabled = enabled) } }); Text("自动打开应用后显示任务说明", Modifier.padding(top = 12.dp)) }
            if (state.settings.taskOverlayEnabled) {
                Text("显示时长", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5 to "5秒", 8 to "8秒", 15 to "15秒", 0 to "直到手动关闭").forEach { (value, label) -> FilterChip(state.settings.taskOverlaySeconds == value, { vm.updateSettings { it.copy(taskOverlaySeconds = value) } }, { Text(label) }) } }
            }
            Text("操作反馈音", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text("任务开始（轻量确认音）")
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){com.justenough.planner.task.TaskSoundPlayer.startChoices.forEach{(id,label)->FilterChip(state.settings.startSound==id,{vm.updateSettings{it.copy(startSound=id)};com.justenough.planner.task.TaskSoundPlayer.play(id)},{Text(label)})}}
            Text("任务完成（奖励音）")
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){com.justenough.planner.task.TaskSoundPlayer.completionChoices.forEach{(id,label)->FilterChip(state.settings.completionSound==id,{vm.updateSettings{it.copy(completionSound=id)};com.justenough.planner.task.TaskSoundPlayer.play(id)},{Text(label)})}}
            Text("每个时间安排还可以单独关闭到点开始音。",style=MaterialTheme.typography.bodySmall)
            TextButton({ vm.updateSettings { it.copy(taskRingtone="BUILTIN",customRingtoneUri="",ringtoneDurationSeconds=120,ringtoneVibrate=true,ringtoneVolumeRamp=true,feedbackSoundEnabled=true,startSound="LEAF",completionSound="WARM") } }) { Text("恢复默认声音") }
        }
        item {
            Section("主动分析")
            Text("每日自动上限：${state.settings.autoAnalysisLimit}次")
            Slider(state.settings.autoAnalysisLimit.toFloat(), { value -> vm.updateSettings { it.copy(autoAnalysisLimit = value.toInt()) } }, valueRange = 0f..40f, steps = 39)
            Row { Switch(state.settings.wifiOnlyAnalysis, { enabled -> vm.updateSettings { it.copy(wifiOnlyAnalysis = enabled) } }); Text("仅Wi-Fi自动分析", Modifier.padding(12.dp)) }
            Text("聊天和分析保留90天；到期前会提示你选择删除、延长或导出。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Section("归档计划")
            val archived=state.plans.filter{it.archived}
            if(archived.isEmpty()) Text("暂无归档计划") else archived.forEach { plan -> var restoreMenu by remember{mutableStateOf(false)};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(plan.name,Modifier.padding(top=12.dp));Row{Box{TextButton({restoreMenu=true}){Text("恢复")};DropdownMenu(restoreMenu,{restoreMenu=false}){PlanQuadrants.ALL.forEach{q->DropdownMenuItem({Text(quadrantLabel(q))},{vm.restorePlan(plan.id,q);restoreMenu=false})}}};TextButton({vm.permanentlyDeletePlan(plan.id)}){Text("永久删除")}}} }
        }
        item {
            Section("加密备份")
            Text("备份包含计划、设置和历史，不包含API Key。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({createBackup.launch("刚刚好-${java.time.LocalDate.now()}.jeplan")}){Text("导出")};OutlinedButton({openBackup.launch("*/*")}){Text("恢复")}}
        }
        item { TextButton(vm::clearAiConfiguration) { Text("清除AI配置") } }
    }
    if(backupMode=="EXPORT"||backupMode=="INSPECT") AlertDialog(onDismissRequest={backupMode="";backupPassword=""},title={Text(if(backupMode=="EXPORT")"导出加密备份" else "校验备份")},text={OutlinedTextField(backupPassword,{backupPassword=it},label={Text("至少8位密码")},visualTransformation=PasswordVisualTransformation())},confirmButton={TextButton(onClick={backupUri?.let{uri->if(backupMode=="EXPORT")vm.exportBackup(uri,backupPassword)else vm.inspectBackup(uri,backupPassword)};if(backupMode=="EXPORT"){backupMode="";backupPassword=""}else backupMode="PREVIEW"},enabled=backupPassword.length>=8){Text("继续")}},dismissButton={TextButton({backupMode="";backupPassword=""}){Text("取消")}})
    if(backupMode=="PREVIEW"&&backupPreview!=null) AlertDialog(onDismissRequest={backupMode="";backupPassword="";vm.clearBackupPreview()},title={Text("确认恢复")},text={val p=backupPreview!!;Text("备份日期：${java.time.Instant.ofEpochMilli(p.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}\n计划：${p.database.plans.size} 项\n时间安排：${p.database.blocks.size} 项\n执行记录：${p.database.runs.size} 条\n\n恢复失败时不会覆盖当前数据。")},confirmButton={TextButton({backupUri?.let{vm.restoreBackup(it,backupPassword)};backupMode="";backupPassword=""}){Text("确认恢复")}},dismissButton={TextButton({backupMode="";backupPassword="";vm.clearBackupPreview()}){Text("取消")}})
    if(showPetConsent) AlertDialog(onDismissRequest={showPetConsent=false;vm.updateSettings{it.copy(petPromptDismissed=true)}},title={Text("让小满显示在其他应用上层")},text={Text("开启后，小满会作为一个可拖动的小入口显示在屏幕边缘。它只读取刚刚好里的计划与完成情况，不读取其他应用、文件或聊天；可随时隐藏或彻底关闭。")},confirmButton={TextButton({showPetConsent=false;vm.updateSettings{it.copy(petPromptDismissed=true)};vm.setPetVisibility(PetVisibility.VISIBLE);if(!Settings.canDrawOverlays(context))context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:${context.packageName}")))}){Text("了解并继续")}},dismissButton={TextButton({showPetConsent=false;vm.updateSettings{it.copy(petPromptDismissed=true,petEnabled=false,petVisibility=PetVisibility.DISABLED)}}){Text("暂不开启")}})
}

@Composable
private fun PlanDialog(onDismiss: () -> Unit, initial: PlanEntity? = null, presetQuadrant: String = "", onDelete: (() -> Unit)? = null,onBind:(()->Unit)?=null,onUnbind:(()->Unit)?=null,onTest:(()->Unit)?=null, onSave: (String, String, Int, String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }; var goal by remember(initial) { mutableStateOf(initial?.minimumGoal.orEmpty()) }; var minutes by remember(initial) { mutableStateOf((initial?.estimatedMinutes ?: 20).toString()) };var quadrant by remember(initial,presetQuadrant){mutableStateOf(initial?.quadrant?.takeIf(PlanQuadrants::isValid) ?: presetQuadrant.takeIf(PlanQuadrants::isValid).orEmpty())}
    val context=LocalContext.current
    val appLabel=remember(initial?.appPackage){initial?.appPackage?.let{pkg->runCatching{context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString()}.getOrDefault(pkg)}}
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(initial==null)"新建计划" else "编辑计划") }, text = { Column { OutlinedTextField(name, { name = it }, label = { Text("计划名称") }); OutlinedTextField(goal, { goal = it }, label = { Text("目标") }); OutlinedTextField(minutes, { minutes = it }, label = { Text("预计分钟") });Text("所属象限",Modifier.padding(top=8.dp),fontWeight=FontWeight.SemiBold);QuadrantSelector(quadrant){quadrant=it};if(initial!=null){HorizontalDivider(Modifier.padding(vertical=10.dp));Text("绑定应用",fontWeight=FontWeight.SemiBold);Text(appLabel?:"尚未绑定",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Row{TextButton(onBind?:{}){Text(if(appLabel==null)"选择应用" else "更换")};if(appLabel!=null){TextButton(onTest?:{}){Text("测试打开")};TextButton(onUnbind?:{}){Text("解绑")}}}};onDelete?.let{TextButton(it){Text("永久删除",color=MaterialTheme.colorScheme.error)}} } }, confirmButton = { TextButton({ onSave(name, goal, minutes.toIntOrNull() ?: 20,quadrant) },enabled=name.isNotBlank()&&goal.isNotBlank()&&PlanQuadrants.isValid(quadrant)) { Text("保存") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
private fun ScheduleDialog(plans: List<PlanEntity>, onDismiss: () -> Unit, initial: TimeBlockEntity? = null, initialPlanIds: List<Long> = emptyList(), todayMode: Boolean = false, onSave: (TimeBlockEntity, List<Long>) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var startMinute by remember(initial) { mutableIntStateOf(initial?.startMinute ?: 360) }
    var endMinute by remember(initial) { mutableIntStateOf(initial?.endMinute ?: 420) }
    var startText by remember(initial) { mutableStateOf(clock(startMinute)) }
    var endText by remember(initial) { mutableStateOf(clock(endMinute)) }
    var startSoundEnabled by remember(initial) { mutableStateOf(initial?.startSoundEnabled ?: true) }
    var autoStartEnabled by remember(initial) { mutableStateOf(initial?.autoStartEnabled ?: true) }
    var autoOpenAppEnabled by remember(initial) { mutableStateOf(initial?.autoOpenAppEnabled ?: false) }
    var vibrateEnabled by remember(initial) { mutableStateOf(initial?.vibrateEnabled ?: true) }
    var anchor by remember(initial) { mutableStateOf(initial?.kind == PlanKinds.ANCHOR) }
    var repeatMode by remember(initial) { mutableStateOf(if (initial?.dateEpochDay != null) "ONCE" else if ((initial?.weekdayMask ?: 127) == 127) "DAILY" else "WEEKLY") }
    val weekdays = remember(initial) { mutableStateListOf<Int>().apply { val mask = initial?.weekdayMask ?: 127; (1..7).filterTo(this) { day -> mask and (1 shl (day - 1)) != 0 } } }
    var onceDate by remember(initial) { mutableStateOf(initial?.dateEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()) }
    val selected = remember(initialPlanIds) { mutableStateListOf<Long>().apply { addAll(initialPlanIds) } }
    val context = LocalContext.current
    var previewing by remember { mutableStateOf(false) }
    var ringSource by remember { mutableStateOf("BUILTIN") }
    var ringUri by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val s = context.appContainer.settings.state.first()
        ringSource = s.taskRingtone
        ringUri = s.customRingtoneUri
    }
    DisposableEffect(Unit) { onDispose { RingtonePreview.stop() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (todayMode) "安排" else if (initial == null || initial.id == 0L) "添加时间安排" else "编辑时间安排") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(startText, { value -> startText = normalizeClockInput(value); parseClock(startText)?.let { startMinute = it } }, Modifier.weight(1f), label = { Text("开始 HH:mm") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(endText, { value -> endText = normalizeClockInput(value); parseClock(endText)?.let { endMinute = it } }, Modifier.weight(1f), label = { Text("结束 HH:mm") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(-15, -5, 5, 15).forEach { delta -> AssistChip({ startMinute = (startMinute + delta).coerceIn(0, 1439); startText = clock(startMinute) }, { Text(if (delta > 0) "+$delta" else "$delta") }) }
                    AssistChip({ startMinute = (startMinute / 60) * 60; startText = clock(startMinute) }, { Text("整点") })
                    listOf(30, 60, 90).forEach { duration -> AssistChip({ endMinute = (startMinute + duration).coerceAtMost(1440); endText = clock(endMinute) }, { Text("${duration}分") }) }
                }
                if (endMinute <= startMinute) Text("结束时间必须晚于开始时间", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("时间段类型", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!anchor, { anchor = false }, { Text("自由选择（可多选）") })
                    FilterChip(anchor, { anchor = true; if (selected.size > 1) { val first = selected.first(); selected.clear(); selected.add(first) } }, { Text("固定任务（仅一项）") })
                }
                Text(if (anchor) "这个时间段必须做的计划" else "这个时间段可以自由选择的计划（可多选）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    plans.forEach { plan ->
                        Row(Modifier.fillMaxWidth().clickable { if (plan.id in selected) selected.remove(plan.id) else { if (anchor) selected.clear(); selected.add(plan.id) } }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(plan.id in selected, null)
                            Text(plan.name, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Switch(startSoundEnabled, { startSoundEnabled = it })
                    Text("到点播放开始音", style = MaterialTheme.typography.bodySmall)
                    TextButton({ if (previewing) { RingtonePreview.stop(); previewing = false } else { RingtonePreview.play(context, ringSource, ringUri); previewing = true } }) { Text(if (previewing) "试听关闭" else "试听", style = MaterialTheme.typography.labelMedium) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Switch(autoStartEnabled, { autoStartEnabled = it }); Text("到点自动开始任务", style = MaterialTheme.typography.bodySmall)
                    Switch(autoOpenAppEnabled, { autoOpenAppEnabled = it }); Text("到点自动打开绑定应用", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Switch(vibrateEnabled, { vibrateEnabled = it }); Text("到点振动", style = MaterialTheme.typography.bodySmall)
                }
                if (!todayMode) {
                    HorizontalDivider()
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    Text("重复", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("DAILY" to "每天", "WEEKLY" to "星期", "ONCE" to "一次").forEach { (mode, label) -> FilterChip(repeatMode == mode, { repeatMode = mode }, { Text(label) }) } }
                    if (repeatMode == "WEEKLY") Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, label -> FilterChip(i + 1 in weekdays, { if (i + 1 in weekdays) weekdays.remove(i + 1) else weekdays.add(i + 1) }, { Text(label) }) } }
                    if (repeatMode == "ONCE") TextButton({ android.app.DatePickerDialog(context, { _, y, m, d -> onceDate = LocalDate.of(y, m + 1, d) }, onceDate.year, onceDate.monthValue - 1, onceDate.dayOfMonth).show() }) { Text("日期：$onceDate") }
                }
            }
        },
        confirmButton = {
            TextButton({
                val mask = if (repeatMode == "DAILY") 127 else weekdays.fold(0) { acc, day -> acc or (1 shl (day - 1)) }
                val base = initial ?: TimeBlockEntity(name = name, kind = PlanKinds.CHOICE, startMinute = 360, endMinute = 420)
                val finalName = if (name.isBlank()) (plans.firstOrNull { it.id in selected }?.name ?: "安排") else name.trim()
                onSave(
                    base.copy(
                        name = finalName,
                        kind = if (anchor) PlanKinds.ANCHOR else PlanKinds.CHOICE,
                        startMinute = startMinute,
                        endMinute = endMinute,
                        weekdayMask = if (todayMode || repeatMode == "ONCE") 127 else mask,
                        dateEpochDay = if (todayMode) LocalDate.now().toEpochDay() else if (repeatMode == "ONCE") onceDate.toEpochDay() else null,
                        startSoundEnabled = startSoundEnabled,
                        autoStartEnabled = autoStartEnabled,
                        autoOpenAppEnabled = autoOpenAppEnabled,
                        vibrateEnabled = vibrateEnabled,
                    ),
                    selected.toList(),
                )
            }, enabled = selected.isNotEmpty() && parseClock(startText)?.let { it < 1440 } == true && parseClock(endText) != null && endMinute > startMinute && (repeatMode != "WEEKLY" || weekdays.isNotEmpty())) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

private fun normalizeClockInput(value:String):String{val digits=value.filter(Char::isDigit).take(4);return if(digits.length<=2)digits else digits.take(2)+":"+digits.drop(2)}
private fun parseClock(value:String):Int?{val parts=value.trim().split(':');if(parts.size!=2)return null;val h=parts[0].toIntOrNull()?:return null;val m=parts[1].toIntOrNull()?:return null;return if((h in 0..23&&m in 0..59)||(h==24&&m==0))h*60+m else null}

@Composable private fun SnoozePlanDialog(plan: PlanDetails, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var customMinutes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("稍后开始：${plan.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("到点后会自动开始任务并响铃；绑定应用会尝试打开。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(5L to "5分钟", 10L to "10分钟", 15L to "15分钟", 30L to "30分钟", 60L to "1小时", 120L to "2小时").forEach { (minute, label) -> AssistChip({ onPick(minute) }, { Text(label) }) } }
            OutlinedTextField(customMinutes, { customMinutes = it }, Modifier.fillMaxWidth(), label = { Text("自定义分钟数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (customMinutes.toLongOrNull()?.let { it > 0 } == true) TextButton({ onPick(customMinutes.toLong()) }, Modifier.fillMaxWidth()) { Text("按自定义时间开始") }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

private object RingtonePreview {
    private var current: android.media.Ringtone? = null

    fun play(context: android.content.Context, source: String, uri: String) {
        stop()
        val value = when (source) {
            "SYSTEM_ALARM" -> android.media.RingtoneManager.getRingtone(context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
            "SYSTEM_NOTIFICATION" -> android.media.RingtoneManager.getRingtone(context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
            "CUSTOM" -> uri.takeIf { it.isNotBlank() }?.let { android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(it)) }
            else -> null
        }
        if (value == null) com.justenough.planner.task.TaskSoundPlayer.play("BELL")
        else { current = value; value.play() }
    }

    fun stop() {
        current?.stop()
        current = null
    }
}

@Composable private fun Section(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun openPlan(context: android.content.Context, id: Long) = context.startActivity(Intent(context, TaskConfirmActivity::class.java).putExtra("plan_id", id))
private fun statusText(value: String) = when (value) { "TESTING" -> "正在测试"; "CONNECTED" -> "已连接"; "FAILED" -> "连接失败"; else -> "尚未配置" }
