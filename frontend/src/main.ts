import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import '@fontsource/poppins/latin-400.css'
import '@fontsource/poppins/latin-500.css'
import '@fontsource/poppins/latin-600.css'
import '@fontsource-variable/noto-sans-sc'
import '@fontsource-variable/lora/wght.css'
import 'element-plus/dist/index.css'
import './styles.css'
import './business.css'
import './governance.css'
import './workflow.css'
import './operations.css'
import './video.css'

const app = createApp(App).use(router)
void router.isReady().then(() => app.mount('#app'))
