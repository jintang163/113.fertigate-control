import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '总览仪表盘' }
  },
  {
    path: '/fields',
    name: 'fields',
    component: () => import('@/views/FieldsView.vue'),
    meta: { title: '田块管理' }
  },
  {
    path: '/fields/:id',
    name: 'field-detail',
    component: () => import('@/views/FieldDetailView.vue'),
    meta: { title: '田块详情' }
  },
  {
    path: '/crops',
    name: 'crops',
    component: () => import('@/views/CropsView.vue'),
    meta: { title: '作物模型' }
  },
  {
    path: '/devices',
    name: 'devices',
    component: () => import('@/views/DevicesView.vue'),
    meta: { title: '设备管理' }
  },
  {
    path: '/rotation',
    name: 'rotation',
    component: () => import('@/views/RotationView.vue'),
    meta: { title: '轮灌调度' }
  },
  {
    path: '/jobs',
    name: 'jobs',
    component: () => import('@/views/JobsView.vue'),
    meta: { title: '灌溉作业' }
  },
  {
    path: '/ledger',
    name: 'ledger',
    component: () => import('@/views/LedgerView.vue'),
    meta: { title: '灌肥台账' }
  },
  {
    path: '/alarms',
    name: 'alarms',
    component: () => import('@/views/AlarmsView.vue'),
    meta: { title: '告警中心' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = `${(to.meta.title as string) || ''} - 水肥一体化智能灌溉`
})

export default router
