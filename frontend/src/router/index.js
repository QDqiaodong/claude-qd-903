import { createRouter, createWebHistory } from 'vue-router'
import YardMatrix from '../views/YardMatrix.vue'
import BerthShoreline from '../views/BerthShoreline.vue'
import CraneEfficiency from '../views/CraneEfficiency.vue'
import GateLifecycle from '../views/GateLifecycle.vue'

const routes = [
  { path: '/', redirect: '/yard' },
  { path: '/yard', name: 'yard', component: YardMatrix, meta: { title: '堆场箱位平面矩阵', code: 'YARD' } },
  { path: '/berths', name: 'berths', component: BerthShoreline, meta: { title: '泊位岸线平面图', code: 'BERTH' } },
  { path: '/cranes', name: 'cranes', component: CraneEfficiency, meta: { title: '岸桥作业效率', code: 'CRANE' } },
  { path: '/gates', name: 'gates', component: GateLifecycle, meta: { title: '闸口与箱生命周期', code: 'GATE' } }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
