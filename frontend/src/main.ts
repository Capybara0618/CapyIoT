import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import 'element-plus/dist/index.css'
import './styles.css'
import './business.css'

const app = createApp(App).use(router)
void router.isReady().then(() => app.mount('#app'))
