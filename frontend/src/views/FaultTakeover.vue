<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { craneApi, takeoverApi, setOperator, getOperator } from '../api'

defineProps({ yardSelection: Object, zones: Array })

const cranes = ref([])
const sessions = ref([])
const detail = ref(null)
const loading = ref(false)
const operator = ref(getOperator())
const selected = ref([])
const handoverTarget = ref('')
const handoverMode = ref('FROM_CONFIRMED_STAGE')
const faultReason = ref('')
const faultCraneId = ref(null)
const faultDialog = ref(false)
const autoRefresh = ref(true)
const recoveringCrane = ref(false)
let timer = null

const operators = ['张伟', '李静', '王磊']
const itemStateName = {
  FROZEN_PENDING: '待执行冻结',
  FROZEN_EXECUTING: '执行中冻结',
  PAUSED: '暂停/等待恢复',
  HANDED_OVER: '已转交',
  HANDOVER_REJECTED: '接管被拒',
  RESUMED: '已恢复原机'
}
const itemStateClass = {
  FROZEN_PENDING: 'frozen', FROZEN_EXECUTING: 'executing', PAUSED: 'paused',
  HANDED_OVER: 'handed', HANDOVER_REJECTED: 'rejected', RESUMED: 'resumed'
}
const typeName = { UNLOAD: '卸船', LOAD: '装船', SHIFT: '翻倒', PICKUP: '提箱' }
const priorityName = { NORMAL: '普通', URGENT: '加急', VESSEL_CUTOFF: '截船' }
const actionName = {
  FAULT_REPORTED: '故障上报开台', PAUSE: '整批暂停', WAIT_RECOVERY: '等待原机短时恢复',
  CONFIRM_STAGE: '阶段确认', HANDOVER: '转交接管机', HANDOVER_REJECT: '接管被拒',
  CRANE_RECOVERED: '原机修复登记', RESUME: '恢复原机', CLOSE: '关闭方案'
}

const outstanding = computed(() =>
  (detail.value?.items || []).filter(i => ['FROZEN_PENDING', 'FROZEN_EXECUTING', 'PAUSED', 'HANDOVER_REJECTED'].includes(i.itemState)))
const selectedOutstanding = computed(() => outstanding.value.filter(i => selected.value.includes(i.id)))
const allChecked = computed({
  get: () => outstanding.value.length > 0 && outstanding.value.every(i => selected.value.includes(i.id)),
  set: v => { selected.value = v ? outstanding.value.map(i => i.id) : [] }
})
const hasExecuting = computed(() => selectedOutstanding.value.some(i => i.wasExecuting))
const hasPending = computed(() => selectedOutstanding.value.some(i => !i.wasExecuting))

function craneByCode(code) { return cranes.value.find(c => c.code === code) }

const candidateMap = computed(() => Object.fromEntries((detail.value?.candidates || []).map(c => [c.code, c])))

/** 逐指令 × 候选机械的可接收判断（与后端区域/能力/负荷口径一致，前端用于即时禁用与提示） */
function itemBlockers(item, candidate) {
  const blockers = []
  if (candidate.status === 'FAULT') blockers.push('机械故障不可用')
  if (!candidate.workArea) blockers.push('未配置作业区域')
  else {
    const zones = candidate.workArea.split(',').map(s => s.trim())
    // 区域：岸桥指令要求同一泊位，场桥要求覆盖箱所在箱区
    const requiredArea = item.vesselScheduleId
      ? detail.value.failedCrane?.workArea
      : (item.fromPosition?.startsWith('船') ? null : item.fromPosition?.split('-')[0])
    if (requiredArea && !zones.includes(requiredArea)) blockers.push(`区域不覆盖：需要 ${requiredArea}`)
  }
  if (item.weight && Number(item.weight) > Number(candidate.ratedLoad)) {
    blockers.push(`超额定能力：${item.weight}t > ${candidate.ratedLoad}t`)
  }
  return blockers
}

/** 按整批转入顺序模拟负荷：候选当前在途负荷 + 本批逐条累加，超出并发能力的后续指令被拒 */
function loadBlockersAfterBatch(candidate) {
  const capacity = candidate.capacity || 4
  let projected = candidate.inServiceLoad || 0
  const over = []
  for (const item of selectedOutstanding.value) {
    projected += 1
    if (projected > capacity) over.push(item)
  }
  return over
}

/** 选中指令对某候选机械的最坏校验结果 */
function candidateAssessment(code) {
  const candidate = candidateMap.value[code]
  if (!candidate || selectedOutstanding.value.length === 0) return { ok: candidate?.eligible, notes: candidate?.reasons || [] }
  const notes = new Set()
  let ok = true
  for (const item of selectedOutstanding.value) {
    const b = itemBlockers(item, candidate)
    if (b.length) { ok = false; b.forEach(x => notes.add(`${item.instructionNo}：${x}`)) }
  }
  // 负荷按整批累加判定：超出并发能力的后续指令无法接收
  for (const item of loadBlockersAfterBatch(candidate)) {
    ok = false
    notes.add(`${item.instructionNo}：接管后负荷将达上限（${candidate.inServiceLoad}/${candidate.capacity}）`)
  }
  return { ok: ok && candidate.status !== 'FAULT', notes: [...notes] }
}

async function load() {
  loading.value = true
  try {
    const [craneResult, sessionResult] = await Promise.all([craneApi.efficiency(), takeoverApi.sessions()])
    cranes.value = craneResult.data
    sessions.value = sessionResult.data
    if (sessions.value.length && !detail.value) await openSession(sessions.value[0].id)
    else if (detail.value) await openSession(detail.value.id, true)
  } finally { loading.value = false }
}

async function openSession(id, silent = false) {
  try {
    const result = await takeoverApi.detail(id)
    const prevVersion = detail.value?.version
    detail.value = result.data
    // 后提交/他人改动：版本变化且非首次打开时给出明显提示
    if (silent && prevVersion != null && prevVersion !== result.data.version) {
      ElMessage.warning(`方案已更新（v${prevVersion} → v${result.data.version}），请核对剩余可处置指令`)
    }
    selected.value = selected.value.filter(sid => outstanding.value.some(i => i.id === sid))
  } catch (e) { if (!silent) ElMessage.error(e.message) }
}

async function refresh() { await load() }

async function submitFault() {
  if (!faultCraneId.value) return
  try {
    const result = await takeoverApi.reportFault(faultCraneId.value, faultReason.value)
    faultDialog.value = false
    faultReason.value = ''
    detail.value = result.data
    ElMessage.success('故障已上报：关联待执行/执行中指令已冻结，已停止派入新任务')
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function pauseAll(waitRecovery) {
  if (!selectedOutstanding.value.length) return ElMessage.warning('请先勾选指令')
  try {
    detail.value = (await takeoverApi.pause(detail.value.id, selectedOutstanding.value.map(i => i.id))).data
    ElMessage.success(waitRecovery ? '执行中指令已挂起等待原机短时恢复' : '所选指令已整批暂停')
    selected.value = []
  } catch (e) { if (e.status === 409) await conflictReload(e); ElMessage.error(e.message) }
}

async function confirmStage(item, stage) {
  try {
    detail.value = (await takeoverApi.confirmStage(detail.value.id, item.id, stage)).data
    ElMessage.success(`已确认阶段「${stageChainLabel(item, stage)}」`)
  } catch (e) { ElMessage.error(e.message) }
}

async function submitHandover() {
  if (!selectedOutstanding.value.length) return ElMessage.warning('请先勾选指令')
  if (!handoverTarget.value) return ElMessage.warning('请选择接管机械')
  const assessment = candidateAssessment(handoverTarget.value)
  if (!assessment.ok) {
    return ElMessageBox.confirm(
      '以下指令不满足接收条件，提交后将被拒绝并继续冻结：\n' + assessment.notes.slice(0, 6).join('\n'),
      '存在无法接管的指令', { type: 'warning', confirmButtonText: '仍然提交（逐项记录原因）', cancelButtonText: '返回调整' })
      .then(doHandover).catch(() => {})
  }
  await doHandover()
}

async function doHandover() {
  try {
    detail.value = (await takeoverApi.handover(
      detail.value.id, selectedOutstanding.value.map(i => i.id), handoverTarget.value, handoverMode.value)).data
    const rejected = detail.value.items.filter(i => i.itemState === 'HANDOVER_REJECTED')
    ElMessage[rejected.length ? 'warning' : 'success'](
      rejected.length ? `转交完成：${rejected.length} 条被拒并继续冻结，原因见明细` : '所选指令已整批转交')
    selected.value = []
    handoverTarget.value = ''
  } catch (e) {
    if (e.status === 409) return conflictReload(e)
    ElMessage.error(e.message)
  }
}

async function resumeOriginal() {
  // 所有仍未完成、仍挂在原机的冻结/暂停/被拒指令都可恢复；已转交指令不抢回
  const resumable = outstanding.value
  if (!resumable.length) return ElMessage.warning('没有暂停中可恢复给原机械的指令')
  try {
    await ElMessageBox.confirm(
      '仅暂停中的未完成指令会恢复给原机械；已转交指令不会自动抢回。确认恢复？', '原机械恢复确认',
      { type: 'info', confirmButtonText: '确认恢复', cancelButtonText: '取消' })
  } catch { return }
  try {
    detail.value = (await takeoverApi.resume(detail.value.id, resumable.map(i => i.id))).data
    ElMessage.success('暂停中的指令已恢复给原机械')
  } catch (e) { if (e.status === 409) await conflictReload(e); ElMessage.error(e.message) }
}

async function markCraneRecovered() {
  // 仅改变机械运行状态，绝不自动抢回已转交指令；暂停指令仍需“原机恢复确认”
  recoveringCrane.value = true
  try {
    detail.value = (await takeoverApi.craneRecovered(detail.value.id)).data
    ElMessage.success('原机械已置为待机；暂停中的指令仍需通过“原机恢复确认”才会重新派入')
  } catch (e) { ElMessage.error(e.message) } finally { recoveringCrane.value = false }
}

async function closeSession() {
  try {
    detail.value = (await takeoverApi.close(detail.value.id, '全部指令处置完成，方案关闭')).data
    ElMessage.success('接管方案已关闭')
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function conflictReload(error) {
  ElMessage.error(error.message)
  detail.value = (await takeoverApi.detail(detail.value.id)).data
  selected.value = []
}

function stageChainLabel(item, code) {
  return item.stageChain?.find(s => s.code === code)?.label || code
}
function time(t) { return t?.replace('T', ' ').slice(5, 16) || '--' }
function fullTime(t) { return t?.replace('T', ' ').slice(0, 19) || '--' }
function changeOperator(name) { operator.value = name; setOperator(name); ElMessage.success(`当前调度员切换为 ${name}`) }
function openFault() {
  const available = cranes.value.filter(c => c.status !== 'FAULT')
  faultCraneId.value = available[0]?.id || null
  faultReason.value = ''
  faultDialog.value = true
}

onMounted(async () => {
  setOperator(operator.value)
  await load()
  timer = setInterval(() => { if (autoRefresh.value && detail.value) openSession(detail.value.id, true) }, 8000)
})
onUnmounted(() => timer && clearInterval(timer))
</script>

<template>
  <section class="page-panel takeover-page" v-loading="loading">
    <div class="panel-head">
      <div>
        <div class="kicker">FAULT TAKEOVER / DISPATCH CONSOLE</div>
        <h2>机械故障接管台</h2>
        <p>岸桥/场桥故障即冻结待执行与执行中指令、停止派新；整批暂停或转交同型可用机械，区域·能力·负荷逐项把关</p>
      </div>
      <div class="operator-deck glass-card">
        <small>当班调度</small>
        <el-radio-group v-model="operator" size="small" @change="changeOperator">
          <el-radio-button v-for="name in operators" :key="name" :value="name">{{ name }}</el-radio-button>
        </el-radio-group>
        <el-button type="danger" plain size="small" @click="openFault">上报机械故障</el-button>
        <el-button size="small" @click="refresh">刷新方案</el-button>
        <el-checkbox v-model="autoRefresh" size="small">8s 自动同步</el-checkbox>
      </div>
    </div>

    <!-- 进行中方案条：同一故障机械只会有一份 OPEN -->
    <div class="session-strip">
      <button v-for="s in sessions" :key="s.id" :class="['session-pill', { active: detail?.id === s.id }]" @click="openSession(s.id)">
        <i class="fault-dot"></i>
        <b>{{ s.failedCraneCode }}</b>
        <span>{{ s.sessionNo }}</span>
        <em>开台：{{ s.openedBy }} · {{ time(s.openedAt) }}</em>
      </button>
      <div v-if="!sessions.length" class="no-session">当前无进行中的故障接管方案</div>
    </div>

    <template v-if="detail">
      <!-- 方案概览 -->
      <div class="detail-grid">
        <div class="glass-card fault-summary">
          <div class="fault-title">
            <div>
              <small>故障机械 / FAILED CRANE</small>
              <h3>{{ detail.failedCraneCode }} <em>{{ detail.failedCraneType === 'QUAY' ? '岸桥' : '场桥' }}</em></h3>
            </div>
            <div class="version-tag">方案版本 v{{ detail.version }}<span>OPEN</span></div>
          </div>
          <p class="fault-reason">“{{ detail.faultReason }}”</p>
          <div class="fault-meta">
            <span>开台人 <b>{{ detail.openedBy }}</b></span>
            <span>时间 <b>{{ fullTime(detail.openedAt) }}</b></span>
            <span>额定能力 <b>{{ detail.failedCrane?.ratedLoad }}t</b></span>
            <span>作业区域 <b>{{ detail.failedCrane?.workArea || '未配置' }}</b></span>
            <el-button v-if="detail.status === 'OPEN'" size="small" type="success" plain :loading="recoveringCrane" @click="markCraneRecovered">原机已修复（置待机）</el-button>
          </div>
        </div>

        <!-- 船舶进度影响 -->
        <div class="glass-card vessel-progress">
          <div class="block-title">关联船舶进度</div>
          <div v-for="v in detail.vesselProgress" :key="v.vesselScheduleId" class="vessel-row">
            <div class="vessel-head"><b>{{ v.vesselName }}</b><span>{{ v.voyageNo }}</span>
              <em :class="{ jam: v.frozenInstructions }">{{ v.frozenInstructions }} 条冻结</em></div>
            <div class="progress-track"><span :style="{ width: v.completionPercent + '%' }"></span></div>
            <small>完成 {{ v.completedInstructions }}/{{ v.totalInstructions }} · 未完成 {{ v.pendingInstructions }}</small>
          </div>
          <div v-if="!detail.vesselProgress.length" class="empty-hint">本批指令不直接关联船舶（提箱/翻倒）</div>
        </div>
      </div>

      <!-- 批量操作条 -->
      <div class="glass-card action-bar">
        <el-checkbox v-model="allChecked">全选可处置指令（{{ outstanding.length }}）</el-checkbox>
        <span class="selected-hint">已选 {{ selectedOutstanding.length }} 条<template v-if="hasExecuting"> · 含执行中</template></span>
        <div class="action-spacer"></div>
        <el-button @click="pauseAll(false)" :disabled="!selectedOutstanding.length">整批暂停</el-button>
        <el-button type="warning" plain @click="pauseAll(true)" :disabled="!hasExecuting">执行中→等待原机短时恢复</el-button>
        <el-select v-model="handoverTarget" placeholder="选择同型接管机械" style="width:230px">
          <el-option v-for="c in detail.candidates" :key="c.code" :value="c.code" :disabled="false">
            <span>{{ c.code }} · {{ c.workArea || '无区域' }} · {{ c.ratedLoad }}t · 负荷{{ c.inServiceLoad }}/{{ c.capacity }}</span>
          </el-option>
        </el-select>
        <el-radio-group v-if="hasExecuting" v-model="handoverMode" size="small">
          <el-radio-button value="FROM_CONFIRMED_STAGE">从已确认阶段转交</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="submitHandover" :disabled="!selectedOutstanding.length">整批转交</el-button>
        <el-button type="success" plain @click="resumeOriginal">原机恢复确认</el-button>
        <el-button text @click="closeSession">关闭方案</el-button>
      </div>

      <!-- 候选机械校验面板 -->
      <div v-if="handoverTarget" class="glass-card candidate-panel">
        <template v-for="c in detail.candidates.filter(x => x.code === handoverTarget)" :key="c.code">
          <div class="cand-head"><b>{{ c.code }}</b>
            <span>{{ c.craneType === 'QUAY' ? '岸桥' : '场桥' }} · 区域 {{ c.workArea || '未配置' }} · 额定 {{ c.ratedLoad }}t · 负荷 {{ c.inServiceLoad }}/{{ c.capacity }}</span>
          </div>
          <ul v-if="candidateAssessment(c.code).notes.length" class="block-list">
            <li v-for="(n, i) in candidateAssessment(c.code).notes" :key="i">⚠ {{ n }}</li>
          </ul>
          <div v-else class="ok-hint">✓ 所选指令均满足区域覆盖、额定能力与当前负荷要求，可以接收</div>
        </template>
      </div>

      <!-- 指令明细 -->
      <div class="glass-card items-card">
        <div class="block-title">故障指令明细 · 每条指令当前阶段</div>
        <table class="items-table">
          <thead>
            <tr><th style="width:36px"></th><th>指令/箱号</th><th>类型</th><th>路线</th><th>货重</th><th>当前阶段</th><th>状态</th><th>处置/失败原因</th></tr>
          </thead>
          <tbody>
            <tr v-for="item in detail.items" :key="item.id" :class="['state-' + itemStateClass[item.itemState], { disabled: !outstanding.some(i => i.id === item.id) }]">
              <td><el-checkbox v-if="outstanding.some(i => i.id === item.id)" v-model="selected" :value="item.id" /></td>
              <td>
                <b>{{ item.instructionNo }}</b>
                <small>{{ item.containerNo }} · {{ item.containerType }}<i v-if="item.dangerous" class="dg">危</i>
                  <em v-if="item.vesselName"> · {{ item.vesselName }}</em>
                  <em v-else-if="item.priority === 'VESSEL_CUTOFF'" class="cutoff">截船优先</em></small>
              </td>
              <td>{{ typeName[item.instructionType] }}<small>{{ priorityName[item.priority] }}</small></td>
              <td class="route">{{ item.fromPosition }} <span>→</span> {{ item.toPosition }}</td>
              <td>{{ item.weight }}t</td>
              <td>
                <div v-if="item.stageChain?.length" class="stage-chain">
                  <span v-for="(s, idx) in item.stageChain" :key="s.code"
                        :class="{ reached: s.reached, current: s.current }" class="stage-node">
                    {{ s.label }}<i v-if="idx < item.stageChain.length - 1">—</i>
                  </span>
                </div>
                <small v-else class="empty-hint">待执行，尚未进入阶段</small>
              </td>
              <td><span :class="['state-badge', itemStateClass[item.itemState]]">{{ itemStateName[item.itemState] }}</span>
                <small v-if="item.takeoverCraneCode">→ {{ item.takeoverCraneCode }}</small>
                <small v-else-if="item.itemState === 'RESUMED'">→ 原机</small>
              </td>
              <td class="decision-cell">
                <div v-if="item.wasExecuting && outstanding.some(i => i.id === item.id)" class="exec-actions">
                  <small class="impact-hint">
                    等待恢复：船期延迟约 {{ item.impact?.waitRecovery?.vesselDelayMinutes }} 分钟，重复风险 {{ item.impact?.waitRecovery?.repeatRisk }}%<br>
                    阶段转交：延迟约 {{ item.impact?.handover?.vesselDelayMinutes }} 分钟，重复风险 {{ item.impact?.handover?.repeatRisk }}%
                  </small>
                  <el-select v-if="!item.frozenStageConfirmed" size="small" placeholder="现场确认阶段"
                             @change="v => confirmStage(item, v)" style="width:150px">
                    <el-option v-for="s in item.stageChain" :key="s.code" :value="s.code" :label="`确认：${s.label}`" />
                  </el-select>
                  <i v-else class="confirmed">✓ 已确认：{{ stageChainLabel(item, item.frozenStage) }}</i>
                </div>
                <div v-if="item.failureReason" class="fail-reason">✗ {{ item.failureReason }}</div>
                <div v-if="item.itemState === 'HANDED_OVER'" class="ok-text">已从接管机接续，已完成阶段不回滚 · {{ item.decidedBy }}</div>
                <div v-if="item.itemState === 'RESUMED'" class="ok-text">原机恢复继续 · {{ item.decidedBy }}</div>
                <small v-if="item.decidedAt" class="decided-time">{{ time(item.decidedAt) }}</small>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 两选影响说明（执行中指令） -->
      <div v-if="detail.items.some(i => i.wasExecuting)" class="glass-card impact-grid">
        <div class="impact-option wait">
          <h4>① 等待原机械短时恢复</h4>
          <p>指令原地冻结，原机修复后从断点继续，<b>不产生重复操作</b>；但恢复时间不确定，船舶作业线持续停滞，超时直接影响船期。</p>
        </div>
        <div class="impact-option hand">
          <h4>② 从已确认阶段转交</h4>
          <p>已完成阶段不回滚，接管机从现场确认的阶段接续；阶段越早重复挂钩/对位越多，船舶等待更短。<b>禁止仅替换机械编号</b>，否则原机与接管机会同时执行同一条指令。</p>
        </div>
      </div>

      <!-- 决策流水 -->
      <div class="glass-card decisions-card">
        <div class="block-title">决策与失败原因留痕</div>
        <el-timeline>
          <el-timeline-item v-for="d in [...detail.decisions].reverse()" :key="d.id"
            :timestamp="fullTime(d.createdAt)" placement="top"
            :type="d.action.includes('REJECT') ? 'danger' : d.action === 'HANDOVER' ? 'success' : 'primary'">
            <b>{{ actionName[d.action] || d.action }}</b>
            <span v-if="d.instructionNo" class="log-instruction"> · {{ d.instructionNo }}</span>
            <span v-if="d.takeoverCraneCode"> → {{ d.takeoverCraneCode }}</span>
            <p>{{ d.reason }}</p>
            <small>操作人：{{ d.operator }}</small>
          </el-timeline-item>
        </el-timeline>
      </div>
    </template>

    <el-dialog v-model="faultDialog" title="上报机械故障并开启接管台" width="520px">
      <el-form label-position="top">
        <el-form-item label="故障机械">
          <el-select v-model="faultCraneId" style="width:100%">
            <el-option v-for="c in cranes.filter(x => x.status !== 'FAULT')" :key="c.id" :value="c.id"
              :label="`${c.code} · ${c.craneType === 'QUAY' ? '岸桥' : '场桥'} · ${c.status === 'WORKING' ? '作业中' : '待机'}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障现象/原因">
          <el-input v-model="faultReason" type="textarea" :rows="3" placeholder="如：起升机构异响，液压压力波动，司机就地停机" />
        </el-form-item>
      </el-form>
      <p class="dialog-tip">提交后立即冻结该机械全部待执行/执行中指令、停止派入新任务，并生成唯一接管方案。</p>
      <template #footer>
        <el-button @click="faultDialog = false">取消</el-button>
        <el-button type="danger" @click="submitFault">确认故障并开台</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.operator-deck { display:flex;align-items:center;gap:10px;padding:10px 14px;flex-wrap:wrap; }
.operator-deck small { color:#73908b;font-size:11px; }
.session-strip { display:flex;gap:10px;margin:12px 0;flex-wrap:wrap; }
.session-pill { display:flex;flex-direction:column;align-items:flex-start;gap:2px;background:#fff;border:1px solid #d5e1df;border-radius:7px;padding:9px 14px;cursor:pointer;min-width:210px;text-align:left; }
.session-pill.active { border-color:#d54747;box-shadow:0 0 0 2px rgba(213,71,71,.12); }
.session-pill b { color:#173c38;font-size:15px; }
.session-pill span { font:10px monospace;color:#73908b; }
.session-pill em { font-style:normal;font-size:10px;color:#90a4a0; }
.fault-dot { width:9px;height:9px;border-radius:50%;background:#d54747;box-shadow:0 0 0 4px rgba(213,71,71,.12);position:absolute;margin-left:-22px;margin-top:5px; }
.no-session { color:#8aa29d;font-size:13px;padding:18px; }
.detail-grid { display:grid;grid-template-columns:1.2fr 1fr;gap:14px;margin-bottom:14px; }
.fault-summary { padding:16px 18px; }
.fault-title { display:flex;justify-content:space-between;align-items:flex-start; }
.fault-title small { color:#73908b;font:10px monospace; }
.fault-title h3 { margin:4px 0 0;font-size:24px;color:#b5302b; }
.fault-title h3 em { font-style:normal;font-size:12px;color:#73908b;margin-left:8px; }
.version-tag { font:11px monospace;color:#00695c;background:#eef5f3;padding:5px 9px;border-radius:5px;display:flex;flex-direction:column;align-items:center;gap:3px; }
.version-tag span { color:#fff;background:#d54747;border-radius:3px;padding:1px 7px;font-size:9px; }
.fault-reason { color:#5c3030;background:#fdf0f0;border-left:3px solid #d54747;padding:8px 11px;margin:10px 0;font-size:13px; }
.fault-meta { display:flex;gap:18px;flex-wrap:wrap;font-size:11px;color:#78908b; }
.fault-meta b { color:#315c57; }
.vessel-progress { padding:14px 16px; }
.block-title { font-size:14px;font-weight:700;color:#173c38;margin-bottom:11px; }
.vessel-row { margin-bottom:12px; }
.vessel-head { display:flex;align-items:center;gap:9px;font-size:12px;margin-bottom:4px; }
.vessel-head span { color:#8aa29d;font-size:10px; }
.vessel-head em { margin-left:auto;font-style:normal;font-size:10px;color:#00695c;background:#eef5f3;padding:2px 7px;border-radius:9px; }
.vessel-head em.jam { color:#b5302b;background:#fdf0f0; }
.progress-track { height:6px;background:#e4ecea;border-radius:6px;overflow:hidden; }
.progress-track span { display:block;height:100%;background:linear-gradient(90deg,#00695c,#20aa98); }
.vessel-row small { color:#8aa29d;font-size:10px; }
.empty-hint { color:#9ab0ac;font-size:11px; }
.action-bar { display:flex;align-items:center;gap:10px;padding:11px 15px;margin-bottom:14px;flex-wrap:wrap; }
.selected-hint { font-size:11px;color:#73908b; }
.action-spacer { flex:1; }
.candidate-panel { padding:12px 16px;margin-bottom:14px;border-left:3px solid #f0b92e; }
.cand-head { display:flex;gap:10px;align-items:baseline;font-size:13px;margin-bottom:7px; }
.cand-head span { color:#78908b;font-size:11px; }
.block-list { margin:0;padding-left:2px;list-style:none;display:flex;flex-direction:column;gap:4px; }
.block-list li { color:#b5302b;font-size:12px;background:#fdf0f0;padding:5px 9px;border-radius:4px; }
.ok-hint { color:#0a7a53;font-size:12px;background:#effaf6;padding:7px 10px;border-radius:4px; }
.items-card { padding:14px 16px;margin-bottom:14px;overflow-x:auto; }
.items-table { width:100%;border-collapse:collapse;font-size:12px;min-width:1100px; }
.items-table th { text-align:left;color:#73908b;font-size:10px;letter-spacing:1px;padding:7px 9px;border-bottom:1px solid #e4ecea; }
.items-table td { padding:10px 9px;border-bottom:1px solid #eef3f2;vertical-align:top; }
.items-table tr.disabled { opacity:.62; }
.items-table b { color:#173c38; }
.items-table small { display:block;color:#8aa29d;font-size:10px;margin-top:2px; }
.items-table .dg { background:#d54747;color:#fff;font-style:normal;font-size:9px;padding:0 4px;border-radius:3px;margin-left:4px; }
.items-table .cutoff { color:#b5302b;font-style:normal; }
.route span { color:#aac0bc; }
.state-badge { display:inline-block;padding:2px 8px;border-radius:10px;font-size:10px;white-space:nowrap; }
.state-badge.frozen { background:#e7eef0;color:#4b6368; }
.state-badge.executing { background:#fdf3e0;color:#a06a08; }
.state-badge.paused { background:#f0f0f3;color:#5c5c70; }
.state-badge.handed { background:#e5f6f0;color:#0a7a53; }
.state-badge.rejected { background:#fdf0f0;color:#b5302b; }
.state-badge.resumed { background:#eaf1fb;color:#2456a6; }
.state-FROZEN_EXECUTING { background:#fffaf2; }
.state-HANDOVER_REJECTED { background:#fffafa; }
.stage-chain { display:flex;align-items:center;flex-wrap:wrap;gap:2px;max-width:250px; }
.stage-node { font-size:10px;color:#b3c4c1; }
.stage-node i { margin:0 3px;color:#dbe5e3;font-style:normal; }
.stage-node.reached { color:#0a7a53; }
.stage-node.current { color:#b5302b;font-weight:700; }
.exec-actions { display:flex;flex-direction:column;gap:5px;max-width:300px; }
.impact-hint { color:#7a6a48;font-size:10px;line-height:1.5;background:#fdf8ee;padding:5px 8px;border-radius:4px; }
.confirmed { color:#0a7a53;font-size:10px;font-style:normal; }
.fail-reason { color:#b5302b;font-size:11px;background:#fdf0f0;padding:5px 8px;border-radius:4px;max-width:300px; }
.ok-text { color:#0a7a53;font-size:11px; }
.decided-time { color:#b3c4c1 !important; }
.impact-grid { display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-bottom:14px; }
.impact-option { padding:14px 17px; }
.impact-option h4 { margin:0 0 7px;font-size:14px; }
.impact-option p { margin:0;font-size:12px;color:#55706b;line-height:1.7; }
.impact-option.wait { border-left:3px solid #94a7a4; }
.impact-option.hand { border-left:3px solid #f0b92e; }
.decisions-card { padding:14px 18px; }
.log-instruction { color:#73908b;font:11px monospace; }
.decisions-card p { margin:4px 0 2px;font-size:12px;color:#55706b; }
.decisions-card small { color:#9ab0ac;font-size:10px; }
.dialog-tip { color:#b5302b;font-size:12px;background:#fdf0f0;padding:8px 11px;border-radius:4px; }
</style>
