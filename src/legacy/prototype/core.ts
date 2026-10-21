/* eslint-disable */
// @ts-nocheck
import { guardView, syncViewRoute, sandboxReady, gotoSandboxRun, gotoSandboxArtifacts, getAnalysisHooks, getAnimationHooks, getCodegenHooks, getWorkHooks } from './hooks'
/* 外包案例库的读取口：hasPublishedCase 供首页卡片「可进入演示」角标，outsourcedCase 供
   详情页 renderDetailPanels，outsourcedCaseError 供详情页空态提示。core 是 ESM，裸标识符
   不会回落到 window，必须显式引入 —— 缺了就是 ReferenceError，并中断整条渲染链：
   缺 hasPublishedCase 时 pcardHTML 抛错，首页轮播/筛选/网格全空。（~迁移改动 2026-10-16） */
import { outsourcedCase, hasPublishedCase, outsourcedCaseError } from '@/stores/outsourcedCases'
import JSZip from 'jszip'
import { saveAs } from 'file-saver'
import html2pdf from 'html2pdf.js'
/* ⚠ 2026-10-19 去掉 html-docx-js 的 import：
   它的 build/templates/*.js 里含 `with (obj) {...}`，Vite 依赖预构建（esbuild → ESM）
   直接报 "With statements cannot be used with the esm output format" 并让 dev server 崩溃。
   改用文件末尾 htmlToDocxBlob()（基于已装的 jszip 生成最小可用 .docx），零新依赖。 */
/* ==================================================================
   ⚠⚠ 本文件自 2026-10-19 起是「手工维护的产物」，不要用生成器覆盖它
   ------------------------------------------------------------------
   scripts/port/build-port.mjs 已经**无法复现**本文件：磁盘产物比它能生成的
   多出约 1050 行 / 89 处，包括但不限于：
     · 成果页双 Tab（论文初稿 / 支撑材料）+ 真实下载（PDF / Word / ZIP）
     · 外包案例库（outsourcedCase / hasPublishedCase 的接入）
     · E 题（p5 SEM）数据 BOOK_E / PY_CODE_E / RUN_OUT_E / SUPPORT_E / ALL_ANIMS_E
     · 动画 rail（animRail*）、微网回放 openMicrogridReplay
   用仓库里能找到的原型 HTML 重新分片后再跑生成器，自检会报 89 处差异，
   脚本会**跳过写入**并告警（不会再中断，但也千万别加 --force 硬覆盖）。

   ⇒ 改这个文件：直接改，改完在提交信息里说明（它就是产物，不是生成的）。
   ⇒ 谁跑 `node scripts/port/build-port.mjs --force`：上面这一批会全部丢失。
   ================================================================== */
/* ==================================================================
   原型逻辑层 · Stage 1（由 scripts/port/build-port.mjs 自动生成，勿手改）

   内容：数模演易原型v2.html 里那块 1,175,971 字符的应用脚本，**逐字搬运**。
   改动只有六类，都有据可查：
     1. 剔除 1,015,738 字符的截图 base64（占 86%），换成 public/cases/p3/anim-shots/*.png
        的 URL 表 —— 图片本该是文件，不该塞在 JS 里。
     2. 每个顶层声明加上 export，供桥接层还原到 window。
     3. 两处随文档自动执行的 IIFE 改成具名导出（initPrototype / installCarouselDrag）：
        它们在 <script> 里跑时 DOM 已就位，进 ESM 后会在 Vue 挂载前执行并抛异常。
     4. go() 里加两行：登录闸门 + 路由同步（标了「~迁移改动」）。
     5. core 3 处 + bridge 3 处的 `$('stream-in')` 改成 `workHost()`：对话区按任务
        分桶（见桥接层的「工作台任务仓」），前台任务的容器挂在 #stream-in 里，
        换台只搬容器本身。桥接层内部那几处（workHost / workHostShow / workHostEl）
        保留 `$('stream-in')` —— 那读的正是「槽位」，不是「当前任务的容器」。
     6. （已撤）原 header 多一行 import 把 utils/analysis/intent.ts（意图路由，纯函数）
        引进来做「流程指令 / 题目相关问答 / 无关话题」分流。2026-10-14 起前端不再做
        意图粗筛（方向盘交给智能体，见 stores/analysis.ts 的 askAgent），该模块与 import
        一并删除。
     其余 161 个函数、全部数据，逐字未改。

   为什么加 @ts-nocheck：这是未类型化的原生 JS（162 个函数全部隐式 any），
   强类型化要么改写全部代码、要么引入 2000 处 any 注解，两者都会让
   「行为与原型逐字一致」这件事失去保障。所以本文件先豁免类型检查，
   由 scripts/port/probe-*.mjs 做真机断言，随后逐函数 Vue 化。

   ⚠ 标了「~待 Vue 化」的函数直接操作 DOM，是后续拆成 Vue 组件的目标；
     在那之前，它们与 Vue 模板共用同一套 id / class 契约（见 PrototypeShell.vue）。
   ================================================================== */


/* ================= 数据 ================= */
/* 题库：全部为 2026 年真题（2026 高教社杯国赛 A–E + 2026 MCM/ICM A–F） */
export let PROBLEMS = [
  {id:'p1',title:'中药材热风烘干过程的热湿耦合建模',tag:'2026国赛A题',cat:'国赛本科',year:'2026',level:'本科',type:'机理建模',
   desc:'对圆柱形药材建立预热平衡与恒温干燥两阶段的热质传递模型，求解内部温度与水分浓度演化，并据此确定满足含水率要求的烘干时长。',
   tags:['偏微分方程','数值计算','动边界','Robin 边界'],views:5318,diff:'较难',team:'3 人',cover:'chart',coverImage:'/covers/herb-drying-cover.png',coverFallback:'/hero-assets/herb-drying-cover-v1.png'},
  {id:'p2',title:'无线电干扰源的快速自动定位与清除',tag:'2026国赛B题',cat:'国赛本科',year:'2026',level:'本科',type:'搜索仿真',
   desc:'机器狗搭载测向机在半径 1800 m 区域内找出 10–16 个干扰源，需给出发现—定位—清除的完整策略，并接受模拟器三次正式测试。',
   tags:['交会定位','覆盖构型','路径规划','策略仿真'],views:4702,diff:'较难',team:'3 人',cover:'map',coverImage:'/covers/radio-interference-localization-cover.png',coverFallback:'/hero-assets/radio-interference-localization-cover-v1.png'},
  {id:'p3',title:'微网与外部电网的电力调控策略',tag:'2026国赛C题',cat:'国赛本科',year:'2026',level:'本科',type:'优化决策',
   desc:'小区微网含 12000 kWh 储能与光伏，每日 0:00 制定 144 个时段的购电计划；在信息确定、负载未知、四次预报、电价波动四种情形下分别求解。',
   tags:['混合整数规划','滚动优化','负荷预测','储能调度'],views:6945,diff:'中等',team:'3 人',cover:'bar',demo:true,coverImage:'/covers/microgrid-power-dispatch-cover.png',coverFallback:'/hero-assets/microgrid-power-dispatch-cover-v1.png'},
  {id:'p4',title:'用频装备的时频冲突检测与消解',tag:'2026国赛D题',cat:'国赛专科',year:'2026',level:'高职',type:'组合优化',
   desc:'依据附件用频计划精确检测全部 297 对时频冲突，在消解规则相互矛盾的条件下给出整数规划层面的全局方案，并最大化新增 C 类装备数量。',
   tags:['冲突图','整数规划','资源装填','周期性'],views:2860,diff:'中等',team:'3 人',cover:'net',coverImage:'/covers/frequency-conflict-resolution-cover.png',coverFallback:'/hero-assets/frequency-conflict-resolution-cover-v1.png'},
  {id:'p5',title:'SEM 广告投放策略优化',tag:'2026国赛E题',cat:'国赛专科',year:'2026',level:'高职',type:'数据分析',
   desc:'基于某公司 2025 年 SEM 投放数据构造关键词效益指标，把词划分为黄金 / 重点 / 潜力 / 问题四类，在预算约束下给出选词、出价与投放节奏最优策略。',
   tags:['指标体系','关键词分类','预算分配','CVaR'],views:3124,diff:'入门',team:'3 人',cover:'scatter',demo:true,coverImage:'/covers/sem-ad-optimization-cover.png',coverFallback:'/hero-assets/sem-ad-optimization-cover-v1.png'},
  {id:'p6',title:'智能手机电池耗电建模',tag:'2026美赛A题',cat:'美赛MCM',year:'2026',level:'本科',type:'机理建模',
   desc:'建立锂电池 SOC 的连续时间模型，刻画屏幕亮度、处理器负载、网络活动与温度对耗电的影响，预测不同场景下的剩余可用时间并给出续航建议。',
   tags:['连续时间模型','参数辨识','敏感性分析'],views:3981,diff:'中等',team:'3 人',cover:'line',coverImage:'/covers/smartphone-battery-cover.png',coverFallback:'/hero-assets/smartphone-battery-cover-v1.png'},
  {id:'p7',title:'太空电梯系统建立月球殖民地',tag:'2026美赛B题',cat:'美赛MCM',year:'2026',level:'本科',type:'优化决策',
   desc:'比较太空电梯三银河港、传统火箭与两者组合三种方案，计算运送 1 亿公吨物资到月球的时间表与成本，并分析系统失效、水需求与环境影响。',
   tags:['物流规划','可靠性','多目标权衡'],views:2574,diff:'较难',team:'3 人',cover:'net',coverImage:'/covers/space-elevator-moon-cover.png',coverFallback:'/hero-assets/space-elevator-moon-cover-v1.png'},
  {id:'p8',title:'舞动明星的数据洞察',tag:'2026美赛C题',cat:'美赛MCM',year:'2026',level:'本科',type:'数据分析',
   desc:'依据各赛季评委分与淘汰结果反演每位选手的粉丝投票，比较排名法与百分比法对淘汰的影响，并设计一个更公平的投票机制。',
   tags:['反演估计','投票机制','回归分析'],views:3346,diff:'中等',team:'3 人',cover:'scatter',coverImage:'/covers/dancing-stars-insight-cover.png',coverFallback:'/hero-assets/dancing-stars-insight-cover-v1.png'},
  {id:'p9',title:'成功的体育管理',tag:'2026美赛D题',cat:'美赛ICM',year:'2026',level:'本科',type:'优化决策',
   desc:'为球队老板设计动态决策模型，随战绩与财务变化调整阵容招募与运营策略，量化门票定价、场馆投资等商业决策对球队利润与价值的影响。',
   tags:['动态规划','收益建模','策略仿真'],views:2195,diff:'中等',team:'3 人',cover:'chart',coverImage:'/covers/sports-management-cover.png',coverFallback:'/hero-assets/sports-management-cover-v1.png'},
  {id:'p10',title:'被动式太阳能遮阳设计',tag:'2026美赛E题',cat:'美赛ICM',year:'2026',level:'本科',type:'机理建模',
   desc:'为 Sungrove 与 Borealis 两所大学设计被动太阳能遮阳方案，在建筑朝向、窗墙比与热惰性约束下预测太阳得热并优化供暖制冷能耗。',
   tags:['建筑热工','能耗模拟','气候情景'],views:1832,diff:'中等',team:'3 人',cover:'line',coverImage:'/covers/passive-solar-shading-cover.png',coverFallback:'/hero-assets/passive-solar-shading-cover-v1.png'},
  {id:'p11',title:'生成式 AI 的取舍',tag:'2026美赛F题',cat:'美赛ICM',year:'2026',level:'本科',type:'评价预测',
   desc:'选取 STEM、技工与艺术三类职业，建立数据驱动模型量化生成式 AI 的岗位替代与增强效应，并据此为不同类型高校给出课程调整建议。',
   tags:['数据驱动','政策分析','情景预测'],views:2076,diff:'中等',team:'3 人',cover:'bar',coverImage:'/covers/generative-ai-tradeoffs-cover.png',coverFallback:'/hero-assets/generative-ai-tradeoffs-cover-v1.png'}
];

export let CATS = ['全部','国赛本科','国赛专科','美赛MCM','美赛ICM'];
export let TYPES = ['全部','机理建模','优化决策','数据分析','组合优化','搜索仿真','评价预测'];
export let YEARS = ['全部','2026'];

/* 我的成果 */
export let WORKS = [
  {id:'w1',title:'2026 国赛 C 题 · 微网电力调控（我的解答）',tag:'2026国赛C题',
   time:'2026-09-13 19:42',status:'已完成',share:false,steps:6,
   models:['混合整数线性规划 MILP','随机森林负荷预测','多阶段滚动 LP'],
   paper:'微网与外部电网电力调控策略研究 · 初稿 v4',
   metrics:[['问题一','35126.95 元'],['问题二','1369.18 万元'],['问题三','1535.07 万元']]},
  {id:'w2',title:'2026 国赛 B 题 · 干扰源定位与清除（我的解答）',tag:'2026国赛B题',
   time:'2026-09-11 20:08',status:'已完成',share:true,steps:6,
   models:['交会定位几何模型','正六边形发现点阵','贪心 + 回溯路径'],
   paper:'无线电干扰源快速定位清除策略 · 初稿 v2',
   metrics:[['清除率','100%'],['平均用时','412 s'],['正式测试','3 / 3']]},
  {id:'w3',title:'2026 国赛 D 题 · 时频冲突消解（我的解答）',tag:'2026国赛D题',
   time:'2026-09-09 15:26',status:'进行中',share:false,steps:3,
   models:['冲突图模型','0-1 整数规划'],
   paper:'— 尚未生成 —',
   metrics:[['检测冲突','297 对'],['新增 C 类','+18'],['撤销计划','7 条']]},
  {id:'w4',title:'2026 国赛 E 题 · SEM 投放策略（我的解答）',tag:'2026国赛E题',
   time:'2026-09-05 11:03',status:'已完成',share:false,steps:6,
   models:['关键词效益指标','K-means 二次校正','CVaR 鲁棒优化'],
   paper:'SEM 广告投放策略优化研究 · 初稿 v1',
   metrics:[['黄金词','38 个'],['无效词','890 个'],['预期 ROI','+14.6%']]}
];

/* ================= 工具 ================= */
export function $(id){return document.getElementById(id)}
export function esc(s){
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
export function hl(code){
  return esc(code)
    .replace(/(#[^\n]*)/g,'<span class="c-com">$1</span>')
    .replace(/\b(def|return|import|from|for|in|if|else|elif|while|with|as|class|try|except|lambda|print|and|or|not|None|True|False|np|pd|plt)\b/g,'<span class="c-kw">$1</span>')
    .replace(/('[^']*'|"[^"]*")/g,'<span class="c-str">$1</span>')
    .replace(/\b(\d+\.?\d*)\b/g,'<span class="c-num">$1</span>');
}
export let toastTimer =null;
export function toast(msg,icon){
  var t=$('toast');
  t.innerHTML=(icon||'')+'<span>'+esc(msg)+'</span>';
  t.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer=setTimeout(function(){t.classList.remove('on')},2200);
}
export function fmt(n){return String(n).replace(/\B(?=(\d{3})+(?!\d))/g,',')}

/* 封面插画 */
export let COVER_ICON = {
  chart:'<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"><path d="M3 20h18"/><path d="M6 20v-7M11 20V7M16 20v-11M21 20V4"/></svg>',
  net:'<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8"><circle cx="12" cy="5" r="2"/><circle cx="5" cy="18" r="2"/><circle cx="19" cy="18" r="2"/><path d="M12 7v6M12 13l-6 3M12 13l6 3" stroke="#fff" stroke-width="1.8"/></svg>',
  bar:'<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"><rect x="3" y="12" width="4" height="8"/><rect x="9" y="7" width="4" height="13"/><rect x="15" y="4" width="4" height="16"/></svg>',
  line:'<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"><path d="M3 17c4 0 5-9 9-9s5 5 9 3"/></svg>',
  map:'<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"><path d="M9 4L3 7v13l6-3 6 3 6-3V4l-6 3-6-3z"/><path d="M9 4v13M15 7v13"/></svg>',
  scatter:'<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8"><circle cx="6" cy="15" r="1.6"/><circle cx="10" cy="9" r="1.6"/><circle cx="14" cy="12" r="1.6"/><circle cx="18" cy="6" r="1.6"/><path d="M4 19c4-1 6-8 14-12" stroke="#fff" stroke-width="1.6"/></svg>'
};
/* ================= 视图切换 ================= */
export let state = {view:'home',cat:'全部',type:'全部',year:'全部',detailId:null,detailTab:'guide',
             workTitle:'',workId:'',drawerOpen:false,drawerType:'',openWork:null,envReady:false};
export let VIEWS = ['home','detail','work','mine'];
export function go(v){
  if(v==='work'&&!state.workTitle){ toast('先在首页输入题目'); return; }
  if(!guardView(v)) return;   /* ~迁移改动：受保护视图先过登录闸门 */
  state.view=v;
  VIEWS.forEach(function(x){
    var el=$('view-'+x);
    if(el) el.classList.toggle('on',x===v);
  });
  $('nv-home').classList.toggle('on',v==='home');
  $('nv-mine').classList.toggle('on',v==='mine');
  $('nv-work').style.display=(v==='work')?'block':'none';
  $('nv-work').classList.toggle('on',v==='work');
  window.scrollTo(0,0);
  if(v==='home') startCarousel();
  if(v==='mine') renderMine();
  syncViewRoute(v);   /* ~迁移改动：把视图同步进 URL，刷新不丢位置 */
}

/* ================= 首页：轮播 ================= */
/**
 * 卡片封面。
 *
 * `coverImage` 指向**后端生成的** `/covers/<slug>-cover.png`（`ProblemCoverController` 落盘）。
 * ⚠ 该控制器至今不存在 ⇒ 这些请求**恒 404**：`onerror` 兜住了视觉，代价是每张卡白打一次
 * 请求、后端日志被它们刷满（真故障会被淹没）。故当前**优先取本地静态封面** `coverFallback`
 * （`/hero-assets/…`，11 张都在）；后端真做出封面生成后，把下面 `var src=` 的候选次序换回来。
 *
 * 两者都没有才落在内联 SVG 图标上 —— 不这么兜的话，任何一张缺失就是碎图。
 */
export function pcardHTML(p){
  var cover;
  var src = p.coverFallback || p.coverImage;
  if(src){
    var spare = src === p.coverImage ? p.coverFallback : p.coverImage;
    var onerr = spare
      ? ' onerror="this.onerror=null;this.src=\''+esc(spare)+'\'"'
      : ' onerror="this.style.display=\'none\'"';
    cover = '<img class="cover-image" src="'+esc(src)+'" alt="'+esc(p.title)
      +' 预览图" loading="lazy"'+onerr+'>';
  } else {
    cover = COVER_ICON[p.cover]||COVER_ICON.chart;
  }
  return '<button class="pcard" onclick="openDetail(\''+p.id+'\')">'+
    '<div class="cover">'+cover+'</div>'+
    '<span class="ptag">'+
      '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M20 6L9 17l-5-5"/></svg>'+
      esc(p.tag)+'</span>'+
    (hasPublishedCase(p.id)?'<span class="ptag" style="margin-left:6px;background:#FFF6DC;color:#B25E00">可进入演示</span>':'')+
    '<h3>'+esc(p.title)+'</h3>'+
    '<p>'+esc(p.desc)+'</p>'+
    '<div class="pfoot">'+
      '<span>'+fmt(p.views)+' 次浏览</span>'+
      '<span class="go">查看<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg></span>'+
    '</div>'+
  '</button>';
}
/* ---------- 首页 Hero 轮播：自动播放 + 按钮 / 圆点 / 鼠标拖拽 ---------- */
export let CAR =[];
export let carIdx =0, carPer=3, carMax=0, carStep=312, carTimer=null;
var carOffset=0, carDotIdx=0;
export function renderCarousel(){
  var demos=PROBLEMS.filter(function(p){return p.demo});
  CAR=demos.concat(PROBLEMS.filter(function(p){return !p.demo}));
  carMeasure();
  var tr=$('car-track');
  if(!tr) return;
  carOffset=CAR.length>carPer?carPer:0;
  carDotIdx=0;
  var before=CAR.slice(-carOffset), after=CAR.slice(0,carPer);
  tr.innerHTML=before.concat(CAR,after).map(pcardHTML).join('');
  carRender();
}
export function carMeasure(){
  var v=$('car-view'); if(!v) return;
  var w=v.clientWidth;
  if(!w||w<200) w=1176;                       /* 视图未渲染时给一个默认宽度 */
  carPer=Math.max(1,Math.min(CAR.length,Math.floor((w+16)/carStep)));
  carMax=Math.max(0,CAR.length-carPer);
  if(CAR.length&&carIdx>=CAR.length) carIdx=0;
}
function carRenderPosition(position,animate){
  var tr=$('car-track'); if(!tr) return;
  if(animate===false) tr.style.transition='none';
  tr.style.transform='translateX('+(-position*carStep)+'px)';
  if(animate===false){ tr.offsetHeight; tr.style.removeProperty('transition'); }
}
function carRenderDots(){
  var d=$('car-dots'), h='', dotCount=carMax+1, active=dotCount?carDotIdx%dotCount:0;
  for(var i=0;i<dotCount;i++) h+='<i class="'+(i===active?'on':'')+'" onclick="carGo('+i+')"></i>';
  if(d) d.innerHTML=h;
}
export function carRender(animate){
  carRenderPosition(carOffset+carIdx,animate);
  carRenderDots();
  var pv=$('car-prev'), nx=$('car-next');
  if(pv) pv.disabled=false;
  if(nx) nx.disabled=false;
}
function carStepDot(steps){
  var dotCount=carMax+1;
  if(dotCount<=0||!steps) return;
  carDotIdx=(carDotIdx+steps%dotCount+dotCount)%dotCount;
  carRenderDots();
}
export function carGo(i){ carDotIdx=Math.max(0,Math.min(carMax,i)); carIdx=Math.max(0,Math.min(carMax,i)); carRender(); startCarousel(); }
export function carMove(d){
  if(CAR.length<=carPer) return;
  carStepDot(d);
  var raw=carIdx+d, tr=$('car-track');
  if(raw>=CAR.length){
    carIdx=0;
    carRenderPosition(carOffset+CAR.length);
    finishCarouselWrap(tr);
  }else if(raw<0){
    carIdx=CAR.length-1;
    carRenderPosition(carOffset-1);
    finishCarouselWrap(tr);
  }else{
    carIdx=raw;
    carRender();
  }
  startCarousel();
}
function finishCarouselWrap(tr){
  if(!tr) return;
  tr.addEventListener('transitionend',function onEnd(e){
    if(e.target!==tr||e.propertyName!=='transform') return;
    tr.removeEventListener('transitionend',onEnd);
    carRender(false);
  });
}
function normalizeCarouselTarget(position){
  if(CAR.length<=carPer) return {idx:0,visual:carOffset};
  var count=CAR.length;
  var idx=((position-carOffset)%count+count)%count;
  var min=carOffset-carPer, max=carOffset+count+carPer-1;
  var visual=carOffset+idx;
  var before=visual-count, after=visual+count;
  if(before>=min&&Math.abs(before-position)<Math.abs(visual-position)) visual=before;
  if(after<=max&&Math.abs(after-position)<Math.abs(visual-position)) visual=after;
  return {idx:idx,visual:visual};
}

export function startCarousel(){
  stopCarousel();
  if(!$('car-view')||carMax<=0) return;
  carTimer=setInterval(function(){
    if(state.view!=='home') return;
    carMove(1);
  },3800);
}
export function stopCarousel(){ if(carTimer){clearInterval(carTimer);carTimer=null;} }
export function installCarouselDrag(){
  var v=document.getElementById('car-view');
  var tr=document.getElementById('car-track');
  if(!v||!tr) return;
  var down=false, sx=0, lastX=0, lastTime=0, velocity=0, moved=false;
  var dragDx=0, dragStart=0;
  var renderDrag=function(dx){
    tr.style.transform='translateX('+(-(dragStart*carStep-dx))+'px)';
  };
  var finishDrag=function(){
    var distance=dragDx;
    var target=Math.round(dragStart-distance/carStep);
    var fling=Math.abs(velocity)>0.45;
    if(fling && Math.abs(distance)<carStep*0.5) target=dragStart+(velocity<0?1:-1);
    var snap=normalizeCarouselTarget(target);
    carStepDot(target-dragStart);
    carIdx=snap.idx;
    tr.classList.remove('grabbing');
    if(snap.visual!==dragStart){
      tr.addEventListener('transitionend',function onEnd(e){
        if(e.target!==tr||e.propertyName!=='transform') return;
        tr.removeEventListener('transitionend',onEnd);
        carRender(false);
      });
    }
    carRenderPosition(snap.visual);
    if(state.view==='home') startCarousel();
  };
  v.addEventListener('mouseenter',stopCarousel);
  v.addEventListener('mouseleave',function(){ if(!down&&state.view==='home') startCarousel(); });
  v.addEventListener('pointerdown',function(e){
    if(e.pointerType==='mouse'&&e.button!==0) return;
    down=true; moved=false; sx=lastX=e.clientX; lastTime=Date.now(); velocity=0; dragDx=0;
    dragStart=carOffset+carIdx;
    tr.classList.add('grabbing'); stopCarousel();
    /* 不在 pointerdown 时就 setPointerCapture —— 那会让 pointerup/mouseup 重定向到
       car-view，浏览器因此不在 pcard 按钮上生成 click，inline onclick 失效。
       改为仅在真正开始拖拽（移动超 4px）时才捕获，纯点击不受影响。 */
  });
  v.addEventListener('pointermove',function(e){
    if(!down) return;
    var now=Date.now(), dx=e.clientX-sx, dt=now-lastTime;
    if(Math.abs(dx)>4){
      if(!moved){ try{ v.setPointerCapture(e.pointerId); }catch(err){} }
      moved=true;
    }
    if(dt>0) velocity=(e.clientX-lastX)/dt;
    lastX=e.clientX; lastTime=now; dragDx=dx;
    renderDrag(dx);
    if(moved) e.preventDefault();
  });
  var release=function(e){
    if(!down) return;
    if(e.pointerId!==undefined){
      try{ v.releasePointerCapture(e.pointerId); }catch(err){}
    }
    down=false;
    finishDrag();
  };
  v.addEventListener('pointerup',release);
  v.addEventListener('pointercancel',release);
  /* 拖动过程中松手会触发卡片的 click，这里拦截掉 */
  tr.addEventListener('click',function(e){
    if(moved){ e.preventDefault(); e.stopPropagation(); moved=false; }
  },true);
  window.addEventListener('resize',function(){ carMeasure(); carRender(); });
}

/* ================= 首页：全部赛题筛选 ================= */
export function frowHTML(label,list,cur,setter){
  return '<div class="frow">'+
    '<span class="lbl">'+esc(label)+'</span>'+
    '<div class="opts">'+
      list.map(function(v){
        return '<button class="chip'+(cur===v?' on':'')+'" onclick="'+setter+'(\''+v+'\')">'+esc(v)+'</button>';
      }).join('')+
    '</div>'+
  '</div>';
}
export function renderFilters(){
  $('filters').innerHTML=
    frowHTML('赛别',CATS,state.cat,'setCat')+
    frowHTML('题型',TYPES,state.type,'setType')+
    frowHTML('年份',YEARS,state.year,'setYear');
}
export function setCat(c){ state.cat=c; renderFilters(); renderGrid(); }
export function setYear(y){ state.year=y; renderFilters(); renderGrid(); }
export function setType(t){ state.type=t; renderFilters(); renderGrid(); }
export function filtered(){
  return PROBLEMS.filter(function(p){
    return (state.cat==='全部'||p.cat===state.cat)
        && (state.year==='全部'||p.year===state.year)
        && (state.type==='全部'||p.type===state.type);
  });
}
export function renderGrid(){
  var list=filtered();
  if(!list.length){ $('grid').innerHTML='<div style="grid-column:1/-1;padding:40px;text-align:center;color:#8A9AAD">该筛选条件下暂无赛题</div>'; return; }
  var cls={'国赛本科':'','国赛专科':'g','美赛MCM':'m','美赛ICM':'o'};
  $('grid').innerHTML=list.map(function(p){
    return '<button class="gcard" onclick="openDetail(\''+p.id+'\')">'+
      '<div class="top">'+
        '<span class="tag-c '+cls[p.cat]+'">'+esc(p.tag)+'</span>'+
        '<span class="mtag">'+esc(p.level)+'</span>'+
      '</div>'+
      '<h4>'+esc(p.title)+'</h4>'+
      '<p>'+esc(p.desc)+'</p>'+
      '<div class="mtags">'+p.tags.map(function(t){return '<span class="mtag">'+esc(t)+'</span>'}).join('')+'</div>'+
    '</button>';
  }).join('');
}

/* ================= 首页：输入 ================= */
export let SAMPLES = [
  '2026 国赛 A 题：中药材热风烘干。圆柱形药材长 25 cm、半径 2 cm，初温 28°C，初始干基含水率 2.55 kg/kg。请建立预热平衡阶段药材内部热传导与水分扩散的数学模型，给出数值计算方法。',
  '2026 国赛 B 题：在半径 1800 m 的圆形区域内有 10–16 个无线电干扰源，机器狗搭载测向机（示向度误差 ±1°，有效接收半径 1000–1500 m）。请给出发现—定位—清除的一般策略，并用模拟器做三次正式测试。',
  '2026 MCM-A 题：建立智能手机锂电池 SOC 的连续时间模型，考虑屏幕亮度、处理器负载、网络活动与环境温度等因素，预测不同使用场景下的耗尽时间并给出续航建议。'
];
export function useSample(i){ $('home-input').value=SAMPLES[i]; $('home-input').focus(); }
export function demoProblem(){
  var p=PROBLEMS.filter(function(x){return x.demo})[0];
  if(!p) return;
  state.detailId=p.id;
  setAnimSet(p.id);   /* 按题切换演示集，避免与其它题的演示登记串扰 */
  if(p.id==='p3'){
    var m=outsourcedCase('p3');
    openMicrogridReplay(m&&m.replayHtml?m.replayHtml:undefined);
    return;
  }
  state.workTitle=p.tag+'：'+p.title;
  startWork(state.workTitle,true);
}
export function pickFile(scope,kind){
  var fi=$('file-input');
  fi.value='';
  fi.onchange=function(){
    var fs=fi.files;
    if(fs&&fs.length){
      var names=[]; for(var i=0;i<fs.length;i++) names.push(fs[i].name);
      /* ~接入改动：真的把文件交出去（校验 + 暂存，开题或在工作台里选完即上传）。
         原型这里是把文件名写进输入框冒充「已上传」，文件读完就丢 —— 这次换掉 */
      attachPick(scope,fs,names,kind||'data');
    }
  };
  fi.click();
}
export function sendFromHome(){
  var v=$('home-input').value.trim();
  /* ~接入改动：只传了题目文件也算「可以开始演易」*/
  var paper=attachProblemTitle('home');
  if(!v&&!paper){ toast('请先在输入框写下题目，或点「上传题目」把题目文件传上来'); return; }
  /* ~接入改动（2026-10-14）：与 analysisRef 同一判据的准入闸 —— 题面太短又没有
     题目文件就拦下，别开出一条注定跑不动的任务（对话区连一句话都发不出去）。 */
  var H=getAnalysisHooks();
  var ref=(H&&H.resolveCase)?H.resolveCase('', v||paper, false, attachHasProblem('home')):null;
  if(!ref){ toast('这道题还开不了工：把题干写全一点（不少于 20 字），或传一份题目文件'); return; }
  startWork(v||paper);
}
/* ================= 题目详情页 ================= */
export let ICON = {
  guide:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>',
  anim:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>',
  code:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 18l6-6-6-6"/><path d="M8 6l-6 6 6 6"/></svg>',
  result:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="M22 4L12 14.01l-3-3"/></svg>',
  paper:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'
};
export function openDetail(id){
  state.detailId=id;
  ANIM_FULL=true;   /* 详情页看的是平台已完赛案例：5 张演示全部可回看 */
  setAnimSet(id);   /* 按题切换演示集：C 题→mod-，E 题→emod- */
  var p=PROBLEMS.filter(function(x){return x.id===id})[0];
  if(!p) return;
  $('d-title').textContent=p.title;
  $('d-meta').innerHTML=[
    '<span>'+ICON.guide.replace('width="16" height="16"','width="14" height="14"')+esc(p.tag)+'</span>',
    '<span>赛别：'+esc(p.cat)+'</span>',
    '<span>年份：'+esc(p.year)+'</span>',
    '<span>层次：'+esc(p.level)+'</span>',
    '<span>难度：'+esc(p.diff)+'</span>',
    '<span>建议队伍：'+esc(p.team)+'</span>',
    '<span>'+fmt(p.views)+' 次浏览</span>'
  ].join('');
  renderDetailPanels(p);
  setDetailTab('guide');
  go('detail');
}
export function setDetailTab(t){
  state.detailTab=t;
  if(t==='result'){
    dlvTab='paper';
    var resultPanel=$('panel-result');
    var resultProblem=PROBLEMS.filter(function(x){return x.id===state.detailId})[0];
    if(resultPanel&&resultProblem) resultPanel.innerHTML=renderUnifiedResultPanel(resultProblem,outsourcedCase(state.detailId));
  }
  var tabs=$('d-tabs').querySelectorAll('.tab');
  for(var i=0;i<tabs.length;i++) tabs[i].classList.toggle('on',tabs[i].getAttribute('data-tab')===t);
  ['guide','anim','code','result'].forEach(function(k){
    $('panel-'+k).classList.toggle('on',k===t);
  });
  window.scrollTo({top:0,behavior:'smooth'});
}

export let BOOK =[
 {t:'题目重述与信息结构',html:
  '<h3>一、题目重述与信息结构</h3>'+
  '<p>某小区建有微网，内部含<b>光伏电站</b>与<b>储能电站</b>，并与外部电网相连。每日 0:00 需制定当天 144 个时段（10 min/段）的<b>计划购电曲线</b>，全天结束后储能荷电状态须回到初始值。</p>'+
  '<p>四个问题的差别，本质上是<b>决策时可获得的信息</b>不同：</p>'+
  '<ul>'+
  '<li><b>问题一</b>：电价、负载、光伏全部已知 → 纯确定性优化，是后面三问的基准；</li>'+
  '<li><b>问题二</b>：电价确定，负载与光伏当天未知 → 需要预测，缺口按 <span class="mono">5 倍</span>电价紧急购电；</li>'+
  '<li><b>问题三</b>：增加 0:00 / 6:00 / 12:00 / 18:00 四次光伏预报 → 可以中途修正计划，但要付偏差成本（低于计划部分 50%，高于计划部分 1.5 倍）；</li>'+
  '<li><b>问题四</b>：外网电价实时波动且与供电缺口相关 → 重算问题二、三。</li>'+
  '</ul>'+
  '<p><b>储能关键参数</b>（附录1）：容量 12000 kWh，最大充放功率 5000 kW（单时段 ≤ 833.33 kWh），初始 / 0:00 储电量 6000 kWh，运行区间 [1200, 10800] kWh，充放电效率 90%。</p>'+
  '<div class="note">写这一步的关键是画出一张"信息时序图"：每个时刻你知道什么、不知道什么、能改什么。评委一眼就能看出你是否理解了四个问题的递进关系。</div>'},
 {t:'数据预处理与探索性分析',html:
  '<h3>二、数据预处理与探索性分析</h3>'+
  '<p>附件2 给出 2025-01-01 至 12-31 共 365 天 × 144 时段的负载与光伏真值，附件1 给出典型日的电价与光伏预测值。预处理建议：</p>'+
  '<ul>'+
  '<li><b>日历对齐</b>：把每个时段映射为 (年, 月, 日, 时段序号)，并生成星期标签；</li>'+
  '<li><b>缺测与异常</b>：用同星期类型的邻近时段插值；光伏夜间恒为 0，不要当成缺失值；</li>'+
  '<li><b>星期分层</b>：<span class="kv">负载天然分成两类</span> —— 周五/周六一类，周日至周四一类，两类均值差异显著，分开建模可明显降低预测误差；</li>'+
  '<li><b>季节性</b>：光伏出力随月份呈明显起伏，冬夏差可达数倍。</li>'+
  '</ul>'+
  '<div class="formula">MAPE = (1/n)·Σ |y − ŷ| / y × 100%</div>'+
  '<p>探索阶段至少产出三张图：典型日（春分 / 夏至 / 秋分 / 冬至）负载与光伏曲线、全年日总电量热力图、分时电价阶梯图。</p>'+
  '<div class="note">红线：预测时只能用当天 0:00 之前的信息，任何"用了当天真值"的做法都属于数据穿越，会被直接判为无效。</div>'},
 {t:'模型骨架：单日 MILP',html:
  '<h3>三、四个问题共用的 MILP 骨架</h3>'+
  '<p>决策变量（t = 1…144）：</p>'+
  '<ul>'+
  '<li><span class="mono">g_t</span> —— 第 t 时段从外网计划购入的电量（kWh）；</li>'+
  '<li><span class="mono">c_t</span> / <span class="mono">d_t</span> —— 储能充电量 / 放电量（kWh）；</li>'+
  '<li><span class="mono">S_t</span> —— 时段末储电量（kWh）；</li>'+
  '<li><span class="mono">u_t ∈ {0,1}</span> —— 第 t 时段储能是否处于充电状态。</li>'+
  '</ul>'+
  '<div class="formula">min　Σ_t p_t · g_t\n'+
  's.t.　g_t + d_t + PV_t = L_t + c_t　　　　   (功率平衡)\n'+
  '　　　S_t = S_{t−1} + c_t − d_t / η　　　　   (储能递推, η = 0.9)\n'+
  '　　　1200 ≤ S_t ≤ 10800,　S_0 = S_144 = 6000\n'+
  '　　　c_t ≤ 833.33·u_t,　d_t ≤ 833.33·(1 − u_t)   (充放互斥)</div>'+
  '<p>最后一行是本题最容易被忽略的约束。若不加 0-1 变量（或等价的 Big-M / 互补约束），求解器会得到"同一时段既充又放"的假最优解，因为充放效率差会让能量在纸面上凭空增值。</p>'+
  '<div class="note">灵敏度好素材：改变 S_min（1200 → 1500 kWh）后总费用只上升约 0.35%，说明运行区间下界不是敏感参数；而效率的写法（单侧折算 vs 双侧开方）对结果的影响明显更大。</div>'},
 {t:'从问题一到问题四的升级',html:
  '<h3>四、从问题一到问题四的模型升级路径</h3>'+
  '<h3 style="font-size:14.5px">问题二：预测 + 安全裕量</h3>'+
  '<p>负载用随机森林（输入为 Lag-1…Lag-7 同 时段值、时段序号、星期类别），光伏用近 3 日同 时段滑动平均。训练按月滚动，只用历史数据。<span class="kv">关键是引入安全裕量系数 α</span>：把预测负载上抬 α 后再做计划，用"多买一点"的确定性成本去对冲"缺口按 5 倍结算"的风险。</p>'+
  '<div class="formula">L̂_t = (1 + α) · L_pred,t</div>'+
  '<p>α 需要在网格上搜索：太小则紧急购电频繁，太大则日常浪费，全年总费用对 α 呈明显的 U 型曲线。本题最优取 α = 0.02。</p>'+
  '<h3 style="font-size:14.5px">问题三：多阶段滚动 + 双向违约</h3>'+
  '<p>每次预报到手后重新优化剩余时段。若新方案带来的期望节省小于设定阈值 θ，则维持原计划不变，避免"为了修正而修正"。实际与计划的偏差按规则双向结算：</p>'+
  '<div class="formula">费用 = Σ p_t·g_t^计划 + 0.5·Σ p_t·(g_t^计划 − g_t^实际)⁺ + 1.5·Σ p_t·(g_t^实际 − g_t^计划)⁺ + 5·Σ p_t·e_t</div>'+
  '<h3 style="font-size:14.5px">问题四：电价不确定</h3>'+
  '<p>电价不再是固定的分时表，而是随时间波动的随机变量，且与系统供电缺口正相关。应对办法是先按峰谷平与缺口水平分层做电价预测，再把不确定电价作为输入重算问题二、三。</p>'},
 {t:'结果、灵敏度与稳健性',html:
  '<h3>五、结果与灵敏度分析</h3>'+
  '<p>四个问题的参考结果（报告区间 2025-02-01 至 12-31，共 334 天）：</p>'+
  '<table><tr><th>问题</th><th>场景</th><th>关键结果</th></tr>'+
  '<tr><td>问题一</td><td>确定信息 · 单日</td><td>购电量 59482.70 kWh / 费用 35126.95 元</td></tr>'+
  '<tr><td>问题二</td><td>负载光伏未知 · 全年</td><td>1369.18 万元，紧急购电占 6.0%（α = 0.02）</td></tr>'+
  '<tr><td>问题三</td><td>四次光伏预报</td><td>1535.07 万元，紧急购电降至 1.31%</td></tr>'+
  '<tr><td>问题四-2</td><td>电价波动 · 重算问题二</td><td>1471.70 万元</td></tr>'+
  '<tr><td>问题四-3</td><td>电价波动 · 重算问题三</td><td>1787.47 万元</td></tr></table>'+
  '<p>值得写进论文的几个灵敏度结论：</p>'+
  '<ul>'+
  '<li>是否引入 0:00 之外的预报时刻：逐个加入后总费用单调下降但<b>边际收益递减</b>，说明引入是有必要的，但不必追求更多时刻；</li>'+
  '<li>紧急购电倍数从 3 增到 10，全年总费用近似<b>线性上升</b>（约 1535 → 1740 万元）—— 阶段一求解不依赖紧急购电价格，价格只影响最终结算；</li>'+
  '<li>波动电价下把安全裕量提高到 α = 0.05，紧急购电量确实大幅下降，但全年总费用反而回升到 1827.27 万元 —— 因为超取部分要按 1.5 倍结算，<b>"多买"同样是成本</b>。这说明最优安全裕量取决于结算规则本身，不是一个固定经验值。</li>'+
  '</ul>'+
  '<div class="note">最后一条是很好的创新点：它说明"最优安全裕量"依赖于结算规则本身，而不是一个固定经验值。很多论文只做到 α 网格搜索就停了。</div>'},
 {t:'模型评价与论文写作',html:
  '<h3>六、模型评价与论文写作要点</h3>'+
  '<h3 style="font-size:14.5px">优点</h3>'+
  '<ul>'+
  '<li>四个问题共用一个 MILP 骨架，只改输入与结算规则，论文结构紧凑、代码可复用；</li>'+
  '<li>明确论证了"必须用预测值而不是真实值"：用真实数据回代时紧急购电恒为 0，机制失去意义 —— 这是很有说服力的反证检验；</li>'+
  '<li>对照组包含拥有完美信息的三种启发式策略，检验严格。</li>'+
  '</ul>'+
  '<h3 style="font-size:14.5px">局限与改进</h3>'+
  '<ul>'+
  '<li>随机森林是点预测，未给出区间；下一步可用分位数回归 / LightGBM 输出预测分布，做随机规划或分布鲁棒优化；</li>'+
  '<li>未考虑储能循环老化成本，容量衰减在长周期滚动中不可忽略；</li>'+
  '<li>电价预测仅按峰谷平分层，未刻画缺口—电价之间的动态反馈。</li>'+
  '</ul>'+
  '<div class="note">论文结构建议：摘要（务必写关键数字）→ 问题重述 → 假设与符号说明（<b>记得补"单位"列</b>）→ 数据分析 → 问题一～四模型与求解 → 灵敏度与稳健性 → 结论 → 参考文献 → 附录（代码 + result1.xlsx ～ result4-3.xlsx 文件说明）。</div>'}
];
/* E 题（p5 · SEM 广告投放）实训指导书 —— 与 BOOK 同构，按题切换 */
export let BOOK_E =[
 {t:'题目重述与信息结构',html:
  '<h3>一、题目重述与信息结构</h3>'+
  '<p>某公司 2025 年在搜索引擎投放 SEM 广告，素材组织为<b>推广单元 × 关键词 × 出价</b>三层。附件给出三张表：附件1 为推广单元与关键词的基础信息，附件2 为全年逐日投放日志（展现 / 点击 / 消费 / 转化），附件3 为关键词分类指标。需在预算约束下给出选词、出价与投放节奏的最优策略。</p>'+
  '<p>四个问题的递进，本质是<b>决策粒度</b>与<b>不确定性</b>不同：</p>'+
  '<ul>'+
  '<li><b>问题一</b>：检验原投放策略合理性，识别效益时间规律与星期规律 → 描述性诊断；</li>'+
  '<li><b>问题二</b>：按四象限把关键词分为黄金 / 重点 / 潜力 / 问题四类 → 分类治理；</li>'+
  '<li><b>问题三</b>：建立推广单元级投放模型，做选词与预算再分配 → 单元级最优；</li>'+
  '<li><b>问题四</b>：在不确定性下给出六项指标的期望范围 → 风险约束下的决策。</li>'+
  '</ul>'+
  '<p><b>规模核对</b>：全年 7726 个推广单元，5 个方案下共 12 个推广单元参与择优；预算上限取 2025 实测值，因此问题三是「再分配」而非「追加预算」。</p>'+
  '<div class="note">写这一步的关键是画出「三附件 × 四问」对应矩阵：哪张表答哪一问、缺什么字段、要做什么预处理。评委一眼就能看出是否理清了数据结构。</div>'},
 {t:'数据预处理与探索性分析',html:
  '<h3>二、数据预处理与探索性分析</h3>'+
  '<p>预处理建议：</p>'+
  '<ul>'+
  '<li><b>字段对齐</b>：把附件2 的逐日日志按推广单元 × 关键词 × 日期拼成主表，与附件1 的出价、附件3 的指标做 join；</li>'+
  '<li><b>缺测与异常</b>：消费为 0 但点击 > 0 的记录按同单元同星期插值；转化漏斗顶端为 0 的关键词单独标记，不参与均值；</li>'+
  '<li><b>星期规律</b>：效益（转化 / 消费）按星期分层，周一至周四一类、周五一类、周末一类，三类差异显著；</li>'+
  '<li><b>时间规律</b>：效益在一天内呈「双峰」（上午 9–11、晚间 20–22），投放节奏应随峰调整。</li>'+
  '</ul>'+
  '<div class="formula">效益率 = 转化量 / 消费量　；　点击率 = 点击量 / 展现量</div>'+
  '<p>探索阶段至少产出三张图：全年日效益热力图、星期 × 时段效益矩阵、四象限分类散点（横轴消费、纵轴转化）。</p>'+
  '<div class="note">红线：分类阈值只能用历史数据标定，任何「用当天转化给当天分类」的做法都属于数据穿越，会被直接判为无效。</div>'},
 {t:'效益指标与饱和响应函数',html:
  '<h3>三、效益指标与饱和响应函数</h3>'+
  '<p>效益指标体系把原始投放量折成可比的效益分数：</p>'+
  '<ul>'+
  '<li><span class="mono">展现 → 点击</span>：点击率；</li>'+
  '<li><span class="mono">点击 → 转化</span>：转化率；</li>'+
  '<li><span class="mono">转化 / 消费</span>：效益率（ROI 的倒数口径）。</li>'+
  '</ul>'+
  '<p>关键词出价 B 与获得流量呈<b>饱和型</b>关系：低出价段近似线性上升，超过拐点后边际递减，最终趋于上限。</p>'+
  '<div class="formula">f(B) = A · B / (B + τ)　；　f′(B) = A · τ / (B + τ)²</div>'+
  '<p>τ 为半饱和出价（f = A/2 处的 B 值），A 为流量上限。参数由历史同单元同档位数据拟合；拟合时以残差平方和为目标，对 τ 施加非负约束。</p>'+
  '<div class="note">饱和型而非线性型的关键意义：高回报关键词优先填到拐点，超过拐点后再投不如把预算转给其他单元 —— 这是问题三预算再分配的依据。</div>'},
 {t:'从问题一到问题四的升级',html:
  '<h3>四、从问题一到问题四的模型升级路径</h3>'+
  '<h3 style="font-size:14.5px">问题一：策略合理性诊断</h3>'+
  '<p>不用建最优模型，只检验原策略：按效益率排序，看头部关键词是否拿到匹配预算、尾部是否还在浪费。效益时间规律与星期规律用同期均值对比即可。</p>'+
  '<h3 style="font-size:14.5px">问题二：关键词四象限分类</h3>'+
  '<p>以消费为横轴、转化为纵轴（或效益率为纵轴），用双阈值把关键词分入四类：</p>'+
  '<ul>'+
  '<li><b>黄金词</b>：高消费高转化 → 维持；</li>'+
  '<li><b>重点词</b>：低消费高转化 → 加预算；</li>'+
  '<li><b>潜力词</b>：低消费低转化但点击率尚可 → 择机承接；</li>'+
  '<li><b>问题词</b>：高消费低转化 → 释放预算。</li>'+
  '</ul>'+
  '<p>阈值用历史数据分位数标定，并做 ±10% 扰动检验稳健性。</p>'+
  '<h3 style="font-size:14.5px">问题三：单元级投放模型</h3>'+
  '<p>在预算上限（= 2025 实测）约束下，求各推广单元的出价与预算分配最大化总期望转化：</p>'+
  '<div class="formula">max　Σ_u A_u · B_u / (B_u + τ_u)　s.t.　Σ_u B_u ≤ B_total,　B_u ≥ 0</div>'+
  '<p>由 KKT 条件，最优解满足「各单元边际效益相等」：<span class="mono">A_u · τ_u / (B_u + τ_u)² = λ</span>（公共 λ）。预算转移规则：问题词释放 → 重点词承接 + 潜力词择机承接，总量守恒。</p>'+
  '<h3 style="font-size:14.5px">问题四：不确定性下的决策</h3>'+
  '<p>把 A_u、τ_u 视为随机变量，用 CVaR 约束给出六项指标（总展现 / 总点击 / 总消费 / 总转化 / 效益率 / 紧急购流量级）在置信水平下的期望范围，并以区间形式交付。</p>'},
 {t:'结果、灵敏度与稳健性',html:
  '<h3>五、结果与灵敏度分析</h3>'+
  '<p>分类阈值 ±10% 扰动下，四类关键词的成员变动不超过 8%，说明阈值稳健；单元级再分配后总期望转化相对原策略有提升，问题词预算占比明显下降。</p>'+
  '<p>值得写进论文的几个灵敏度结论：</p>'+
  '<ul>'+
  '<li><b>半饱和出价 τ</b>：τ 偏大时最优解更「平均」，τ 偏小时更「集中」—— 预算集中度对 τ 敏感；</li>'+
  '<li><b>预算上限</b>：再分配在「上限 = 2025 实测」下成立，若上限上浮 10%，增量预算优先填重点词而非黄金词（黄金词已近饱和）；</li>'+
  '<li><b>CVaR 置信水平</b>：从 90% 提到 95%，六项指标区间展宽，效益率下界明显下移 —— 说明尾部风险主要来自效益端而非消费端。</li>'+
  '</ul>'+
  '<div class="note">最后一条是很好的创新点：它把不确定性的量级定位在效益端，而不是简单地说「总预算有风险」。</div>'},
 {t:'模型评价与论文写作',html:
  '<h3>六、模型评价与论文写作要点</h3>'+
  '<h3 style="font-size:14.5px">优点</h3>'+
  '<ul>'+
  '<li>四个问题共用一套「效益指标 + 饱和响应」骨架，只改粒度与约束，论文结构紧凑、代码可复用；</li>'+
  '<li>问题三给出 KKT 解析条件，把「预算再分配」从启发式升级为可解释的最优性条件；</li>'+
  '<li>问题四用 CVaR 而非均值，区间交付贴合题面要求。</li>'+
  '</ul>'+
  '<h3 style="font-size:14.5px">局限与改进</h3>'+
  '<ul>'+
  '<li>饱和响应参数 τ 用历史均值拟合，未考虑竞争环境动态变化；下一步可用贝叶斯方法在线更新；</li>'+
  '<li>四象限分类为静态，未刻画关键词类别的迁移；可引入马尔可夫转移；</li>'+
  '<li>CVaR 假设指标近似正态，重尾时需换经验分布。</li>'+
  '</ul>'+
  '<div class="note">论文结构建议：摘要（务必写六项指标区间）→ 问题重述 → 假设与符号说明（<b>记得补"单位"列</b>）→ 数据分析 → 问题一～四模型与求解 → 灵敏度与稳健性 → 结论 → 参考文献 → 附录（代码 + result1.xlsx ～ result4.xlsx 文件说明）。</div>'}
];
/* p1（2026 国赛 A 题 · 中药材热风烘干）实训指导书 —— 与 BOOK 同构，按题切换 */
export let P1_BOOK =[
 {t:'题目重述与信息结构',html:
  '<h3>一、题目重述与信息结构</h3>'+
  '<p>某中药材形状大致呈<b>圆柱形</b>：长 25 cm、半径 2 cm。烘干开始时药材温度 <b>28°C</b>、干基含水率 <b>2.55 kg/kg</b>。热风烘干分<b>预热平衡</b>与<b>恒温干燥</b>两阶段，烘房温度与水分浓度随时间变化（附件1）。四个问题是<b>同一套热湿耦合 PDE 的逐级加深</b>：</p>'+
  '<ul>'+
  '<li><b>问题一</b>：预热平衡阶段（0-1800 s），附录2 常物性 → 建立温度与水分浓度模型，填表1/表2；</li>'+
  '<li><b>问题二</b>：整个烘干过程（2-3 天），附录3 变物性 → 填表3/表4；</li>'+
  '<li><b>问题三</b>：在问题二基础上求各处水分 &lt;0.15 kg/kg 所需烘干时长（h）→ 填表5；</li>'+
  '<li><b>问题四</b>：考虑水分流失导致的<b>半径收缩</b>（附件2：2→1.198 cm），附录4 物性 → 动边界模型，填表6。</li>'+
  '</ul>'+
  '<p><b>关键简化</b>：长径比 25/2 = 12.5 ≫ 1，轴向梯度可忽略 → <b>径向一维</b>柱坐标模型即可（这是本题第一个得分点）。</p>'+
  '<div class="note">这一步的关键是画出"烘房环境 → 表面对流 → 内部扩散"的能量/质量双通道结构图，标清四问共用的控制方程与各自不同的物性来源（附录2/3/4）。评委一眼就能看出你是否抓住了"一套 PDE、四套参数"的主线。</div>'},
 {t:'物理机理与控制方程',html:
  '<h3>二、物理机理与控制方程</h3>'+
  '<p>药材内部同时发生<b>导热</b>与<b>水分扩散</b>，两者在表面通过对流与烘房环境耦合：</p>'+
  '<div class="formula">质量：∂C/∂t = (1/r)·∂/∂r( r·D(C,T)·∂C/∂r )\n'+
  '能量：ρ(C)·cp(C)·∂T/∂t = (1/r)·∂/∂r( r·k(C)·∂T/∂r )</div>'+
  '<p><b>边界条件（Robin 第三类）</b>：</p>'+
  '<ul>'+
  '<li>表面 r=R：&nbsp;-k·∂T/∂r = h·(T|R − Ta(t))，&nbsp;&nbsp;-D·∂C/∂r = km·(C|R − Ca(t))；</li>'+
  '<li>中心 r=0：轴对称，∂T/∂r = ∂C/∂r = 0。</li>'+
  '</ul>'+
  '<p>其中 Ta(t)、Ca(t) 由<b>附件1 线性插值</b>给出（28→50.25°C、0.0196→0.0503 kg/kg，覆盖 0-14400 s 预热段；之后按恒温 50.25°C / 0.0499 处理）。对流换热系数 h = 25 W/(m²·K)，对流传质系数 km = 8×10⁻⁷ m/s。</p>'+
  '<div class="note">本题最易踩坑：① 把"水分浓度"当体积浓度处理——题给的是干基含水率 kg/kg，扩散方程形式不变但物性公式都按它定义；② 忘记中心胞元体积是 r=0 处的四分之一圆（dr²/8），直接套内部胞元格式会导致中心通量凭空翻倍。</div>'},
 {t:'数值方法：有限体积离散',html:
  '<h3>三、数值方法：有限体积法（FV）</h3>'+
  '<p>沿半径取 <b>21 个节点</b>（r = 0, 0.1, …, 2.0 cm，dr = 0.1 cm），对每个环形胞元做质量/能量守恒：</p>'+
  '<ul>'+
  '<li><b>内部胞元</b>（2πr_m·dr）：净通量 = 右面流入 − 左面流出，界面系数取相邻节点算术平均；</li>'+
  '<li><b>中心胞元</b>（体积 dr²/8）：只有右面 flux[0] 流入 → dC[0] = flux[0]/(dr²/8)；</li>'+
  '<li><b>表面半胞元</b>：内部扩散补给（−flux[-1]）减去表面对流传质（km·R·(C|R−Ca)）→ 这是<b>符号最容易写反</b>的地方；</li>'+
  '<li><b>时间推进</b>：显式 Euler，dt = 0.5 s（由 D·dt/dr² &lt; 1/4 限制，全程稳定）。</li>'+
  '</ul>'+
  '<div class="formula">dC[-1] = ( −flux[-1] − km·R·(C[-1]−Ca) ) / ( R·dr )</div>'+
  '<p>问题四引入 <b>Landau（ξ = r/R(t)）坐标</b>把变域问题映射到固定域 [0,1]：扩散项除以 R(t)²，另产生一个指向中心的对流项 (ξ·Ṙ/R)·∂C/∂ξ——收缩"泵送效应"由此显式进入方程。</p>'+
  '<div class="note">自检三件套：① 干燥中后期 Cmax 单调下降且无振荡；② 预热段内部节点水分几乎不动（D 小 4 个量级）；③ 时间步减半结果不变（收敛性）。三条都过，数值解才算站得住。</div>'},
 {t:'从问题一到问题四的模型升级',html:
  '<h3>四、从问题一到问题四的模型升级路径</h3>'+
  '<h3 style="font-size:14.5px">问题一：预热平衡（附录2 常物性）</h3>'+
  '<p>ρ=820 kg/m³、cp=2600 J/(kg·K)、k=0.36 W/(m·K) 均为常数，D = 7×10⁻⁹·e^(−0.89C)。结果特征：<b>热快湿慢</b>——1800 s 全场温度 28→33.4~36.6°C，而水分只在表面约 0.1 cm 薄层内下降（表面 2.55→1.312，内部仍 2.55）。</p>'+
  '<h3 style="font-size:14.5px">问题二：全过程（附录3 变物性）</h3>'+
  '<p>ρ=650+128C、cp=1450+2736·C/(C+1)、k=0.21+0.38·C/(C+1)、D=2.4×10⁻³·e^(−0.45C)·e^(−3850/T)。D 对温度<b>指数敏感</b>：升温到 50°C 后 D 增大约一个量级，干燥明显加速；3 h 时中心水分 2.19、表面 1.01 kg/kg。</p>'+
  '<h3 style="font-size:14.5px">问题三：烘干时长判定</h3>'+
  '<p>沿用问题二模型，监测 Cmax(t) = max_r C(r,t)，首次降到 <b>0.15 kg/kg</b> 的时刻即烘干时长：<b>16.87 h</b>。注意判据是"各处"——中心永远是最后达标点。</p>'+
  '<h3 style="font-size:14.5px">问题四：动边界收缩（附录4 + 附件2）</h3>'+
  '<p>ρ=760+90C、cp=1850+2150·C/(C+1)、k=0.12+0.20·C/(C+1)、D=4.2×10⁻⁴·e^(−0.30C)·e^(−3850/T)；半径按附件2 从 2 cm 收缩到 1.198 cm。虽然扩散路径缩短，但附录4 的 D 比附录3 低约 <b>5 倍</b>，烘干时长反而延长至 <b>21.49 h</b>（结束时 R = 1.207 cm）——<b>物性差异主导、几何收缩次要</b>。</p>'},
 {t:'结果、灵敏度与稳健性',html:
  '<h3>五、结果与灵敏度分析</h3>'+
  '<p>四问的参考结果（有限体积法，21 节点，dt=0.5 s）：</p>'+
  '<table><tr><th>问题</th><th>阶段</th><th>关键结果</th></tr>'+
  '<tr><td>问题一</td><td>1800 s 预热平衡</td><td>中心 33.4188°C / 表面 36.5905°C；表面水分 1.312，内部 2.55</td></tr>'+
  '<tr><td>问题二</td><td>3 h 全过程</td><td>中心 49.81°C（趋近烘房）；中心水分 2.19 / 表面 1.01</td></tr>'+
  '<tr><td>问题三</td><td>Cmax&lt;0.15</td><td>烘干时长 <b>16.87 h</b></td></tr>'+
  '<tr><td>问题四</td><td>动边界收缩</td><td>烘干时长 <b>21.49 h</b>，R 由 2 → 1.207 cm</td></tr></table>'+
  '<p>值得写进论文的几个灵敏度结论：</p>'+
  '<ul>'+
  '<li>扩散系数前置常数 ×2（其余不变），问题三烘干时长约<b>缩短 35%</b>——干燥后期由内部扩散控制，D 是第一敏感参数；</li>'+
  '<li>对流换热系数 h ±20%，只影响预热段升温快慢（±0.4°C 量级），对 16.87 h 的烘干时长几乎无影响——因为后期瓶颈在内部扩散而非表面传热；</li>'+
  '<li>问题四若<b>忽略收缩</b>（固定 R=2 cm + 附录4 物性），烘干时长约 27 h，比动边界模型长 26%——收缩的"路径缩短"效应贡献显著但不足以抵消物性放慢。</li>'+
  '</ul>'+
  '<div class="note">最后一条是很好的讨论点：动边界同时带来两种相反效应——路径变短（加快）与对流泵送指向中心（减慢），净效应是加快约 5.5 h。很多论文只算几何效应不写对流项，会被追问。</div>'},
 {t:'模型评价与论文写作',html:
  '<h3>六、模型评价与论文写作要点</h3>'+
  '<h3 style="font-size:14.5px">优点</h3>'+
  '<ul>'+
  '<li>四问共用一套径向一维热湿耦合 FV 骨架，只换物性函数与网格映射（固定域 / Landau 域），论文结构紧凑、代码可复用；</li>'+
  '<li>有限体积天然守恒，表面通量、中心胞元、动边界对流项都有明确的物理意义，答辩好讲；</li>'+
  '<li>结果给出"热快湿慢""物性主导几何"等机理结论，不止于填表。</li>'+
  '</ul>'+
  '<h3 style="font-size:14.5px">局限与改进</h3>'+
  '<ul>'+
  '<li>忽略轴向梯度与端面效应（长径比 12.5 下端面贡献约 4%，可在论文中用修正因子说明）；</li>'+
  '<li>未显式考虑蒸发潜热在内部的相变耗热（简化为表面效应），更精细可引入蒸发-冷凝源项；</li>'+
  '<li>附件2 半径数据只有 145 个点，长时段用线性插值，收缩速率 Ṙ 的估计有锯齿，可用样条平滑改进。</li>'+
  '</ul>'+
  '<div class="note">论文结构建议：摘要（务必写关键数字 16.87 h / 21.49 h）→ 问题重述 → 假设与符号说明（<b>记得补"单位"列</b>）→ 机理分析与控制方程 → 问题一～四模型与求解 → 灵敏度与稳健性 → 结论 → 参考文献 → 附录（代码 + result1.xlsx ～ result4.xlsx 文件说明）。</div>'}
];

/* 当前激活指导书 —— 进题时由 setAnimSet() 切换；renderDetailPanels 读它而非直接读 BOOK */
export let BOOK_CUR = BOOK;

export function renderDetailPanels(p){
  var managed=outsourcedCase(p.id);
  /* 空态卡片。⛔ 必须用浅色 .case-empty —— 原来用的 .stage-empty 是深色动画台的
     样式（color:#fff + position:absolute;inset:0），放进白色 .card 里会「白字白底」
     且整块脱离文档流，卡片塌成一条 40px 白条：用户看到的就是「案例内容一片空白」。
     2026-10-16 实测（占位矩形落在 0,0,1418,802＝整个视口）后改掉。 */
  var missingCard=function(title,hint){
    return '<div class="card"><div class="card-b"><div class="case-empty"><b>'+esc(title)+'</b><span>'+esc(hint)+'</span></div></div></div>';
  };
  /* 案例库没取到东西时，把真实原因写进空态 —— 否则 401 / 404 / 超时在前端
     长得一模一样，全是「什么都没有」（stores/outsourcedCases.ts 的 error 此前无人读）。 */
  var loadErr=outsourcedCaseError();
  if(!managed){
    var empty=loadErr
      ? missingCard('案例库暂时取不到数据','接口 /api/smyy/cases 返回：'+loadErr+'。请确认后端已启动并已加载案例接口，或当前账号已登录。')
      : missingCard('该题案例内容尚未收录','请管理员进入「案例管理」提交实训指导书、可视化演示、代码和成果。');
    $('panel-guide').innerHTML=empty;
    $('panel-anim').innerHTML=empty;
    $('panel-code').innerHTML=empty;
    $('panel-result').innerHTML=renderUnifiedResultPanel(p,null);
    window._bookReady=true;
    return;
  }
  /* 案例成果统一由双 Tab 面板渲染，避免后端单文件成果破坏页面结构。 */
  if(managed.status==='BLANK'){
    var blankCard=missingCard('该案例为空白占位','请在「案例管理」中提交实训指导书、可视化演示、代码和成果。');
    $('panel-guide').innerHTML=blankCard;
    $('panel-anim').innerHTML=blankCard;
    $('panel-code').innerHTML=blankCard;
    $('panel-result').innerHTML=renderUnifiedResultPanel(p,managed);
    window._bookReady=true;
    return;
  }
  if(managed.guideHtml || managed.animationHtml || managed.animationSegmentsJson || managed.guideFilesJson || managed.codeText || managed.codeFilesJson || managed.resultHtml || managed.resultFilesJson){
    var sectionCard=function(title,content,kind){
      if(!content) return missingCard(title+'尚未录入','请在「案例管理」中替换该板块。');
      if(kind==='code') return '<div class="card"><div class="card-h">'+ICON.code+'<h3>'+title+'</h3><div class="grow"></div><button class="btn-ghost" onclick="toast(\'代码已复制\')">复制</button></div><div class="card-b" style="padding:0"><div class="code-box"><div class="code-top"><span class="f">'+esc(title)+' · Python / 源代码</span><div class="grow"></div><button onclick="toast(\'代码已复制到剪贴板\')">复制</button></div><pre class="code">'+hl(content)+'</pre><div class="run-out"><span class="lbl">SOURCE FILE</span><span>'+esc(title)+'</span></div></div></div></div>';
      var previewFrame=function(html){return '<iframe title="'+esc(title)+'" style="display:block;width:100%;height:760px;border:1px solid #d9e8e7;border-radius:10px;background:#fff" sandbox="allow-scripts" src="data:text/html;charset=utf-8,'+encodeURIComponent(html)+'"></iframe>';};
      if(kind==='animation' && /^\s*<div class="case-animation-viewer"/.test(content)) return '<div class="card case-animation-card"><div class="card-h">'+ICON.anim+'<h3>'+title+'</h3><div class="grow"></div><span class="file-state">分段演示</span></div><div class="card-b" style="padding:16px;background:#f6fbfb">'+content+'</div></div>';
      if(kind==='animation') return '<div class="card"><div class="card-h">'+ICON.anim+'<h3>'+title+'</h3></div><div class="card-b">'+previewFrame(content)+'</div></div>';
      var isHtml=/<!doctype\s+html|<\s*[a-z][^>]*>/i.test(content);
      var body=isHtml ? previewFrame(content) : '<pre class="doc-plain">'+esc(content)+'</pre>';
      var stateText=title.indexOf('成果')>=0?'已上传 · 成果预览':'已上传 · 文档预览';
      return '<div class="card"><div class="card-h"><h3>'+title+'</h3><div class="grow"></div><span class="file-state">'+stateText+'</span></div><div class="card-b">'+body+'</div></div>';
    };
    var animationContent=managed.animationHtml;
    var animationSegments=[];
    if(managed.animationSegmentsJson){
      try{
        var parsedSegments=JSON.parse(managed.animationSegmentsJson)||[];
        if(Array.isArray(parsedSegments)&&parsedSegments.length){
          animationSegments=parsedSegments;
          animationContent='<div class="case-animation-viewer" style="width:100%;max-width:1480px;margin:0 auto"><div class="case-animation-tabs" role="tablist" aria-label="可视化章节" style="display:flex;flex-wrap:wrap;gap:10px;padding:8px 2px 18px">'+animationSegments.map(function(seg,i){return '<button type="button" role="tab" aria-selected="'+(i===0?'true':'false')+'" class="case-anim-tab'+(i===0?' is-active':'')+'" data-case-anim="'+i+'" style="appearance:none;border:1px solid '+(i===0?'#087e75':'#d1e0e0')+';background:'+(i===0?'#087e75':'#fff')+';color:'+(i===0?'#fff':'#35545a')+';min-height:44px;padding:0 22px;border-radius:10px;font-size:14px;font-weight:700;box-shadow:'+(i===0?'0 5px 14px rgba(8,126,117,.2)':'0 2px 6px rgba(20,50,55,.05)')+';cursor:pointer;transition:background .16s ease,color .16s ease,border-color .16s ease,transform .16s ease">'+esc(seg.label||('问题'+(i||'')))+'</button>';}).join('')+'</div><iframe class="case-animation-frame" title="可视化演示" sandbox="allow-scripts allow-forms allow-downloads" referrerpolicy="no-referrer" style="display:block;width:100%;height:clamp(760px, calc(100vh - 210px), 1040px);min-height:760px;border:1px solid #d8e5e5;border-radius:12px;background:#fff;box-shadow:0 8px 26px rgba(20,50,55,.08)"></iframe></div>';
        }
      }catch(e){ animationSegments=[]; }
    }
    var parseFiles=function(json,fallbackName, fallbackText){
      try{var parsed=JSON.parse(json||'[]');if(Array.isArray(parsed)&&parsed.length)return parsed;}
      catch(e){}
      return fallbackText?[{name:fallbackName||'case-file',text:fallbackText}]:[];
    };
    var multiCard=function(title,json,fallbackName,fallbackText,kind){
      var files=parseFiles(json,fallbackName,fallbackText);
      if(!files.length)return sectionCard(title,'',kind);
      return '<div class="card"><div class="card-h"><h3>'+title+'</h3><div class="grow"></div><span class="file-state">'+files.length+' 个文件</span></div><div class="card-b">'+files.map(function(file,i){return sectionCard(file.name||title,file.text||'',kind);}).join('')+'</div></div>';
    };
    $('panel-guide').innerHTML=renderGuideBook(p,managed,sectionCard);
    wireGuideBook();
    $('panel-anim').innerHTML=sectionCard('可视化演示',animationContent,'animation');
    $('panel-code').innerHTML=multiCard('代码',managed.codeFilesJson,managed.codeFileName,managed.codeText,'code');
    $('panel-result').innerHTML=renderUnifiedResultPanel(p,managed);
    if(animationSegments.length){
      var animationPanel=$('panel-anim');
      var animationFrame=animationPanel.querySelector('.case-animation-frame');
      var showAnimation=function(index){
        var segment=animationSegments[index];
        if(!segment) return;
        if(animationFrame){
          animationFrame.src='data:text/html;charset=utf-8,'+encodeURIComponent(segment.html||'');
        }
        animationPanel.querySelectorAll('.case-anim-tab').forEach(function(button,n){
          var active=n===index;
          button.classList.toggle('is-active',active);
          button.setAttribute('aria-selected',active?'true':'false');
          button.style.background=active?'#087e75':'#fff';
          button.style.color=active?'#fff':'#35545a';
          button.style.borderColor=active?'#087e75':'#d1e0e0';
          button.style.boxShadow=active?'0 5px 14px rgba(8,126,117,.2)':'0 2px 6px rgba(20,50,55,.05)';
          button.style.transform=active?'translateY(-1px)':'none';
        });
      };
      animationPanel.onclick=function(event){
        var button=event.target&&event.target.closest?event.target.closest('.case-anim-tab'):null;
        if(button&&animationPanel.contains(button)) showAnimation(Number(button.getAttribute('data-case-anim')||0));
      };
      showAnimation(0);
    }
    window._bookReady=true;
    return;
  }
  /* 指导书 */
  $('panel-guide').innerHTML=
   '<div class="book">'+
     '<div class="toc"><div class="tt">实训指导书目录</div>'+
       BOOK_CUR.map(function(c,i){
         return '<button onclick="jumpBook('+i+')" id="toc-'+i+'">'+(i+1)+'. '+esc(c.t)+'</button>';
       }).join('')+
     '</div>'+
     '<div class="card"><div class="card-h">'+
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#12A594" stroke-width="2" stroke-linecap="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'+
        '<h3>《'+esc(p.title)+'》实训指导书</h3><div class="grow"></div>'+
        '<button class="btn-ghost" onclick="toast(\'下载为演示功能\')">下载 PDF</button>'+
      '</div>'+
      '<div class="card-b" style="padding:0"><div class="doc">'+
        '<h2>'+esc(p.title)+'</h2>'+
        '<div class="dc">'+esc(p.tag)+' · '+esc(p.cat)+' · 实训指导书 · 全文约 12,000 字 · 建议实训时长 8 课时</div>'+
        BOOK_CUR.map(function(c,i){return '<div id="sec-'+i+'">'+c.html+'</div>'}).join('')+
      '</div></div></div>'+
   '</div>';
  /* 动画 */
  $('panel-anim').innerHTML=renderAnimPanel(p);
  /* 代码 */
  $('panel-code').innerHTML=renderCodePanel();
  /* 结果 */
  $('panel-result').innerHTML=renderResultPanel(p);
  window._bookReady=true;
}
export function jumpBook(i){
  var el=$('sec-'+i); if(!el) return;
  window.scrollTo({top:el.getBoundingClientRect().top+window.scrollY-140,behavior:'smooth'});
  for(var k=0;k<BOOK.length;k++){
    var b=$('toc-'+k);
    if(b) b.classList.toggle('on',k===i);
  }
}

/* ── 外包案例「实训指导书」：与内置样例（.book/.toc/.doc）同构的排版 ──
   原 managed 路径把指导书整体塞进 iframe（无大纲 / 无下载 / 尺寸不对），
   这里改为解析正文章节生成左侧目录 + 点击定位 + 下载按钮，与《微网…》样例一致。 */
var _guideSpy=null;
function parseManagedFiles(json,fallbackName,fallbackText){
  try{var parsed=JSON.parse(json||'[]');if(Array.isArray(parsed)&&parsed.length)return parsed;}
  catch(e){}
  return fallbackText?[{name:fallbackName||'case-file',text:fallbackText}]:[];
}
/* 剥离正文里自带的「目录」块（如上传指导书里手写的目录），左侧大纲由 renderGuideBook 生成，
   不在正文里重复出现。命中规则：标题为「目录 / 目 录 / Contents」的章节，删掉该标题及其后
   同级或更高级标题之前的兄弟节点（目录列表）；以及正文内直接内嵌的 .toc/.contents 容器。 */
function stripInlineToc(doc){
  var heads=[].slice.call(doc.querySelectorAll('h1,h2,h3,h4'));
  for(var i=0;i<heads.length;i++){
    var h=heads[i];
    var t=(h.textContent||'').replace(/\s+/g,' ').trim();
    if(/^目\s*录/.test(t) || /^contents$/i.test(t) || /^table\s+of\s+contents$/i.test(t)){
      /* 删除「目录」标题，并删除其后紧邻的连续兄弟节点，直到遇到下一个标题（正文章节开始） */
      var n=h.nextElementSibling;
      while(n){
        var nx=n.nextElementSibling;
        if(/^H[1-4]$/.test(n.tagName)) break; /* 遇到下一个章节标题即停 */
        n.remove(); n=nx;
      }
      h.remove();
      break;
    }
  }
  /* 兼容：正文内直接内嵌的目录容器（本渲染器的左侧大纲不在 doc 内，不受影响） */
  [].slice.call(doc.querySelectorAll('.toc,.contents,.catalog,.mulu')).forEach(function(el){ el.remove(); });
}
function renderGuideBook(p,managed,sectionCard){
  if(!sectionCard) sectionCard=function(){return '';};
  var files=parseManagedFiles(managed.guideFilesJson,managed.guideFileName,managed.guideHtml);
  if(!files.length) return sectionCard('实训指导书','', 'html');
  var parts=files.map(function(f,idx){
    var text=f.text||'';
    if(files.length>1 && f.name) text='<h2 class="guide-file-h">'+esc(f.name)+'</h2>'+text;
    return text;
  });
  var raw=parts.join('\n');
  var bodyMatch=raw.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  var body=bodyMatch?bodyMatch[1]:raw;
  var doc=new DOMParser().parseFromString(body,'text/html');
  /* 去掉脚本与内联样式：避免脚本执行风险与样式污染，章节正文交给页面 .doc 样式排版 */
  [].slice.call(doc.querySelectorAll('script,style')).forEach(function(n){n.remove();});
  /* 去掉正文里自带的「目录」块：左侧大纲已由本渲染器统一生成，避免页面出现两份目录 */
  stripInlineToc(doc);
  var heads=[].slice.call(doc.querySelectorAll('h1,h2,h3,h4'));
  var toc='';var order=0;
  heads.forEach(function(h){
    var text=(h.textContent||'').replace(/\s+/g,' ').trim();
    if(!text) return;
    var id='gsec-'+(order++);
    h.setAttribute('id',id);
    var lvl=Number(h.tagName.charAt(1))||2;
    toc+='<button type="button" class="toc-item lvl'+lvl+'" data-gsec="'+id+'">'+esc(text)+'</button>';
  });
  var inner=doc.body?doc.body.innerHTML:doc.documentElement.innerHTML;
  if(!toc) toc='<div class="toc-empty">本文档未包含章节标题</div>';
  return '<div class="book guide-book">'+
    '<nav class="toc" aria-label="实训指导书目录"><div class="tt">实训指导书目录</div>'+toc+'</nav>'+
    '<div class="card"><div class="card-h">'+
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#12A594" stroke-width="2" stroke-linecap="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>'+
      '<h3>《'+esc(p.title)+'》实训指导书</h3><div class="grow"></div>'+
      '<button type="button" class="btn-ghost" id="guide-download" data-title="'+esc(p.title)+'">下载</button>'+
    '</div>'+
    '<div class="card-b" style="padding:0"><div class="doc" id="guide-doc">'+inner+'</div></div>'+
  '</div>';
}
/* 渲染完成后补绑：下载按钮 + 大纲点击定位 + 滚动高亮 */
function wireGuideBook(){
  var panel=$('panel-guide');if(!panel) return;
  var dl=panel.querySelector('#guide-download');
  if(dl) dl.addEventListener('click',function(){
    var title=dl.getAttribute('data-title')||'实训指导书';
    var docEl=panel.querySelector('#guide-doc');
    downloadGuide(title,docEl?docEl.innerHTML:'');
  });
  var items=[].slice.call(panel.querySelectorAll('.toc-item'));
  if(!items.length) return;
  items.forEach(function(btn){
    btn.addEventListener('click',function(){ jumpGuide(btn.getAttribute('data-gsec')); });
  });
  if(_guideSpy) window.removeEventListener('scroll',_guideSpy);
  var spy=function(){
    var cur=null;
    items.forEach(function(btn){
      var el=document.getElementById(btn.getAttribute('data-gsec'));
      if(el && el.getBoundingClientRect().top<=160) cur=btn;
    });
    items.forEach(function(btn){ btn.classList.toggle('on',btn===cur); });
  };
  _guideSpy=spy;
  window.addEventListener('scroll',spy,{passive:true});
  spy();
}
/* 点击大纲 → 平滑滚动定位到对应章节，并高亮当前项 */
export function jumpGuide(id){
  var el=document.getElementById(id);if(!el) return;
  window.scrollTo({top:el.getBoundingClientRect().top+window.scrollY-140,behavior:'smooth'});
  var panel=$('panel-guide');
  if(panel) [].slice.call(panel.querySelectorAll('.toc-item')).forEach(function(b){
    b.classList.toggle('on',b.getAttribute('data-gsec')===id);
  });
}
/* 把指导书正文导出为自带样式的 .html 文件下载（真实可用，区别于演示占位） */
function downloadGuide(title,html){
  if(!html){ toast('指导书内容为空，无法下载'); return; }
  var page='<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>'+esc(title)+' · 实训指导书</title>'+
    '<style>body{font:15px/1.85 system-ui,"Microsoft YaHei",sans-serif;max-width:840px;margin:36px auto;padding:0 28px;color:#1f2d3d}'+
    'h1,h2{color:#0b6e68}h3{color:#22384a;margin-top:26px}.note{background:#fff8e8;border:1px solid #fbe3b8;padding:10px 14px;border-radius:8px;margin:12px 0}'+
    '.formula{background:#eef7f6;border:1px solid #cfe9e6;padding:10px 14px;border-radius:8px;margin:12px 0}'+
    'ul{padding-left:22px}li{margin:6px 0}table{border-collapse:collapse;width:100%;margin:12px 0}td,th{border:1px solid #ddd;padding:8px 12px}'+
    'img{max-width:100%}@media print{body{margin:0}}</style></head><body>'+html+'</body></html>';
  try{
    var blob=new Blob([page],{type:'text/html;charset=utf-8'});
    var url=URL.createObjectURL(blob);
    var a=document.createElement('a');
    a.href=url;
    a.download=(title||'实训指导书').replace(/[\\/:*?"<>|]/g,'_')+'.html';
    document.body.appendChild(a);a.click();a.remove();
    setTimeout(function(){URL.revokeObjectURL(url);},1000);
    toast('已下载《'+(title||'实训指导书')+'》指导书');
  }catch(e){ toast('下载失败'); }
}

export function animSVG(kind){
  var s='<style>'+
    '@keyframes grow{from{stroke-dashoffset:1200}to{stroke-dashoffset:0}}'+
    '@keyframes rise{from{transform:translateY(150px)}to{transform:translateY(0)}}'+
    '@keyframes pop{0%{opacity:0;r:0}60%{opacity:1}100%{opacity:1;r:4}}'+
    '@keyframes sweep{from{transform:translateX(0)}to{transform:translateX(240px)}}'+
    '@keyframes dashmove{to{stroke-dashoffset:-40}}'+
    '.stage.paused *{animation-play-state:paused!important}'+
  '</style>';
  if(kind==='0'){
    /* 全局分析演示：通用名词 → 数据 → 约束 → 四问关系 */
    return '<svg viewBox="0 0 640 300">'+s+
      '<g stroke="rgba(255,255,255,.22)" stroke-width="1"><path d="M40 268h560"/></g>'+
      /* 四个问题盒子 */
      '<g>'+
      '<rect x="52" y="96" width="118" height="58" rx="10" fill="rgba(46,196,176,.18)" stroke="#2EC4B0" stroke-width="2"/>'+
      '<text x="111" y="122" fill="#B8E8E2" font-size="12.5" text-anchor="middle">问题一</text>'+
      '<text x="111" y="141" fill="rgba(255,255,255,.72)" font-size="11" text-anchor="middle">信息全已知</text>'+
      '<rect x="192" y="96" width="118" height="58" rx="10" fill="rgba(46,196,176,.18)" stroke="#2EC4B0" stroke-width="2"/>'+
      '<text x="251" y="122" fill="#B8E8E2" font-size="12.5" text-anchor="middle">问题二</text>'+
      '<text x="251" y="141" fill="rgba(255,255,255,.72)" font-size="11" text-anchor="middle">负载光伏未知</text>'+
      '<rect x="332" y="96" width="118" height="58" rx="10" fill="rgba(46,196,176,.18)" stroke="#2EC4B0" stroke-width="2"/>'+
      '<text x="391" y="122" fill="#B8E8E2" font-size="12.5" text-anchor="middle">问题三</text>'+
      '<text x="391" y="141" fill="rgba(255,255,255,.72)" font-size="11" text-anchor="middle">四次预报</text>'+
      '<rect x="472" y="96" width="118" height="58" rx="10" fill="rgba(245,166,35,.18)" stroke="#F5A623" stroke-width="2"/>'+
      '<text x="531" y="122" fill="#F8C25A" font-size="12.5" text-anchor="middle">问题四</text>'+
      '<text x="531" y="141" fill="rgba(255,255,255,.72)" font-size="11" text-anchor="middle">电价波动</text>'+
      '</g>'+
      /* 递进箭头 */
      '<g stroke="#7FE0A8" stroke-width="2.2" fill="none" stroke-linecap="round">'+
      '<path d="M172 125h16" style="animation:grow 5s linear infinite"/>'+
      '<path d="M312 125h16" style="animation:grow 5s linear infinite .3s"/>'+
      '<path d="M452 125h16" style="animation:grow 5s linear infinite .6s"/></g>'+
      /* 共用骨架横梁 */
      '<path d="M52 196 C160 182 240 210 340 190 S500 168 590 178" fill="none" stroke="#2EC4B0" stroke-width="3" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite"/>'+
      '<text x="52" y="222" fill="rgba(255,255,255,.62)" font-size="11.5">四个问题共用一套 MILP 骨架</text>'+
      /* 三条约束条 */
      '<g>'+
      '<rect x="52" y="236" width="176" height="22" rx="6" fill="rgba(127,224,168,.16)" stroke="#7FE0A8" stroke-width="1.4" style="animation:rise .8s ease-out .2s both"/>'+
      '<text x="140" y="251" fill="#BFF0D4" font-size="11" text-anchor="middle">1200 ≤ S_t ≤ 10800 kWh</text>'+
      '<rect x="240" y="236" width="150" height="22" rx="6" fill="rgba(127,224,168,.16)" stroke="#7FE0A8" stroke-width="1.4" style="animation:rise .8s ease-out .5s both"/>'+
      '<text x="315" y="251" fill="#BFF0D4" font-size="11" text-anchor="middle">充放 ≤ 833.33 kWh</text>'+
      '<rect x="402" y="236" width="188" height="22" rx="6" fill="rgba(127,224,168,.16)" stroke="#7FE0A8" stroke-width="1.4" style="animation:rise .8s ease-out .8s both"/>'+
      '<text x="496" y="251" fill="#BFF0D4" font-size="11" text-anchor="middle">S(0:00) = S(24:00) = 6000</text>'+
      '</g>'+
      '<text x="40" y="292" fill="rgba(255,255,255,.7)" font-size="12">通用名词　·　数据　·　约束条件　·　四问关系</text>'+
      '<text x="600" y="292" fill="rgba(255,255,255,.7)" font-size="12" text-anchor="end">全局分析</text>'+
    '</svg>';
  }
  if(kind==='2'){
    return '<svg viewBox="0 0 640 300">'+s+
      '<g stroke="rgba(255,255,255,.28)" stroke-width="1">'+
      '<path d="M40 250h560"/><path d="M40 250V30"/></g>'+
      '<path d="M40 250 L120 250 L200 250 L280 250 L360 250 L440 250 L520 250 L600 250" fill="none" stroke="rgba(255,255,255,.2)" stroke-width="2"/>'+
      '<path d="M40 210 C140 200 180 150 240 120 S360 90 420 78 S540 66 600 62" fill="none" stroke="#7FE0A8" stroke-width="3" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite"/>'+
      '<path d="M40 232 C150 226 210 200 280 176 S400 150 470 138 S560 130 600 128" fill="none" stroke="#2EC4B0" stroke-width="3" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite .8s"/>'+
      '<g fill="#F5A623"><circle cx="120" cy="196" r="4" style="animation:pop .6s ease-out both"/><circle cx="200" cy="152" r="4" style="animation:pop .6s ease-out .5s both"/><circle cx="280" cy="132" r="4" style="animation:pop .6s ease-out 1s both"/><circle cx="360" cy="106" r="4" style="animation:pop .6s ease-out 1.5s both"/><circle cx="440" cy="92" r="4" style="animation:pop .6s ease-out 2s both"/><circle cx="520" cy="84" r="4" style="animation:pop .6s ease-out 2.5s both"/><circle cx="600" cy="80" r="4" style="animation:pop .6s ease-out 3s both"/></g>'+
      '<text x="40" y="278" fill="rgba(255,255,255,.7)" font-size="12">2025-02-01 → 12-31 滚动天数</text>'+
      '<text x="596" y="278" fill="rgba(255,255,255,.7)" font-size="12" text-anchor="end">年末</text>'+
      '<text x="46" y="60" fill="#7FE0A8" font-size="12">实际费用累计</text>'+
      '<text x="46" y="78" fill="#2EC4B0" font-size="12">计划费用累计</text>'+
    '</svg>';
  }
  if(kind==='4'){
    return '<svg viewBox="0 0 640 300">'+s+
      '<g stroke="rgba(255,255,255,.22)" stroke-width="1"><path d="M60 260h520"/><path d="M60 260V40"/></g>'+
      '<path d="M60 190 C140 176 180 150 240 158 S330 214 400 190 S520 130 600 152" fill="none" stroke="#2EC4B0" stroke-width="3" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite"/>'+
      '<path d="M60 222 C140 210 180 188 240 196 S330 240 400 220 S520 168 600 186" fill="none" stroke="rgba(46,196,176,.55)" stroke-width="2.4" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite .4s"/>'+
      '<path d="M60 154 C140 140 180 116 240 124 S330 176 400 156 S520 104 600 122" fill="none" stroke="rgba(46,196,176,.5)" stroke-width="2.4" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite .8s"/>'+
      '<path d="M60 116 C120 104 160 92 210 104 S280 148 340 120 S460 110 600 128" fill="none" stroke="#F5A623" stroke-width="3.4" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite .2s"/>'+
      '<g fill="#7FE0A8"><circle cx="240" cy="158" r="4.5" style="animation:pop .6s ease-out .6s both"/><circle cx="400" cy="190" r="4.5" style="animation:pop .6s ease-out 1.2s both"/><circle cx="520" cy="152" r="4.5" style="animation:pop .6s ease-out 1.8s both"/></g>'+
      '<text x="60" y="284" fill="rgba(255,255,255,.7)" font-size="12">0:00</text>'+
      '<text x="600" y="284" fill="rgba(255,255,255,.7)" font-size="12" text-anchor="end">24:00</text>'+
      '<text x="46" y="60" fill="#F5A623" font-size="12">分时电价（元/kWh）</text>'+
      '<text x="46" y="78" fill="#2EC4B0" font-size="12">光伏 / 负载预测带</text>'+
    '</svg>';
  }
  if(kind==='3'){
    return '<svg viewBox="0 0 640 300">'+s+
      '<g stroke="rgba(255,255,255,.22)" stroke-width="1"><path d="M60 260h520"/><path d="M60 260V40"/></g>'+
      '<g fill="#2EC4B0" opacity=".9">'+
      '<rect x="80" y="180" width="34" height="80" rx="4"/><rect x="130" y="140" width="34" height="120" rx="4"/><rect x="180" y="96" width="34" height="164" rx="4"/><rect x="230" y="120" width="34" height="140" rx="4"/><rect x="280" y="72" width="34" height="188" rx="4"/><rect x="330" y="104" width="34" height="156" rx="4"/></g>'+
      '<g fill="#F5A623">'+
      '<rect x="394" y="150" width="34" height="110" rx="4" style="animation:rise 1s ease-out .2s both"/><rect x="444" y="118" width="34" height="142" rx="4" style="animation:rise 1s ease-out .5s both"/><rect x="494" y="92" width="34" height="168" rx="4" style="animation:rise 1s ease-out .8s both"/><rect x="544" y="76" width="34" height="184" rx="4" style="animation:rise 1s ease-out 1.1s both"/></g>'+
      '<path d="M60 200 C200 190 300 150 420 128 S540 100 600 88" fill="none" stroke="#7FE0A8" stroke-width="3" stroke-dasharray="1200" stroke-linecap="round" style="animation:grow 5s linear infinite"/>'+
      '<line x1="380" y1="30" x2="380" y2="260" stroke="#F5A623" stroke-width="1.6" stroke-dasharray="6 6" style="animation:dashmove 2s linear infinite"/>'+
      '<text x="386" y="46" fill="#F5A623" font-size="12">开始每日滚动修正计划</text>'+
      '<text x="60" y="284" fill="rgba(255,255,255,.7)" font-size="12">仅 0:00 一次预报</text>'+
      '<text x="600" y="284" fill="rgba(255,255,255,.7)" font-size="12" text-anchor="end">四次预报滚动修正</text>'+
    '</svg>';
  }
  return '<svg viewBox="0 0 640 300">'+s+
    '<g stroke="rgba(255,255,255,.22)" stroke-width="1"><path d="M60 250h520"/><path d="M60 250V40"/></g>'+
    '<g fill="#2EC4B0">'+
    '<circle cx="110" cy="205" r="4.5"/><circle cx="150" cy="176" r="4.5"/><circle cx="196" cy="196" r="4.5"/><circle cx="238" cy="152" r="4.5"/><circle cx="282" cy="170" r="4.5"/><circle cx="320" cy="128" r="4.5"/><circle cx="366" cy="146" r="4.5"/><circle cx="410" cy="112" r="4.5"/><circle cx="452" cy="132" r="4.5"/><circle cx="498" cy="96" r="4.5"/><circle cx="542" cy="116" r="4.5"/><circle cx="584" cy="82" r="4.5"/></g>'+
    '<path d="M80 224 C200 214 300 176 420 148 S540 110 600 92" fill="none" stroke="#F5A623" stroke-width="3.4" stroke-linecap="round" stroke-dasharray="1200" style="animation:grow 5s linear infinite"/>'+
    '<g stroke="rgba(255,255,255,.3)" stroke-width="1" stroke-dasharray="4 4">'+
    '<path d="M110 205V250"/><path d="M238 152V250"/><path d="M366 146V250"/><path d="M498 96V250"/></g>'+
    '<circle cx="320" cy="128" r="9" fill="none" stroke="#7FE0A8" stroke-width="2" style="animation:pop 2s ease-out infinite"/>'+
    '<text x="60" y="278" fill="rgba(255,255,255,.7)" font-size="12">0:00 → 24:00（144 个时段）</text>'+
    '<text x="600" y="278" fill="rgba(255,255,255,.7)" font-size="12" text-anchor="end">购电量（kWh）</text>'+
    '<text x="336" y="120" fill="#7FE0A8" font-size="12">储能触顶时段</text>'+
  '</svg>';
}
export function renderAnimPanel(p){
  var qs=animList();          /* 只含已生成的演示；未走到的步骤不在列表里 */
  var doneN=qs.length;
  var first=qs[0]||null;
  var cur=first?first.k:'0';
  ANIM_CUR=cur;
  var noteTxt = doneN
    ? '本次已生成 <b>'+doneN+' / 5</b> 张：<b>走到哪一步，才生成哪一步的演示</b>，还没走到的步骤不会出现在列表里；已经生成的演示点击列表即可回看。'
    : '演示<b>按流程逐步生成</b>：走到「全局分析」生成<b>演示一</b>，走到「问题一 / 二 / 三 / 四」分别生成<b>演示二～五</b>。当前还没有走到任何一步，列表里暂时是空的。';
  return '<div class="anim-wrap">'+
    '<div>'+
      '<div class="stage'+(first?'':' dim')+'" id="stage">'+(first?fsBtnHTML(cur)+animShotHTML(cur):'')+
        (first?'':'<div class="stage-empty">这里还没有演示内容<span>走到「全局分析」这一步后，会自动生成第一张可视化演示</span></div>')+
      '</div>'+
      '<div class="card" style="margin-top:16px"><div class="card-h"><h3>演示列表（按流程生成）</h3>'+
        '<span class="tag-c" style="font-size:11px">已生成 '+doneN+' / 5</span></div><div class="card-b tight">'+
        (first?'':'<div class="anim-empty">还没有演示。走到「全局分析」这一步后，演示一会出现在这里；之后每走到一个问题，就多一张演示。<br>未走到的步骤不会提前出现在列表里。</div>')+
        qs.map(function(q){
          return '<button class="acard'+(q.k===cur?' open':'')+'" style="border-radius:11px;margin-bottom:8px" onclick="switchAnim(\''+q.k+'\')" id="q-'+q.k+'">'+
            '<span class="ai" style="background:linear-gradient(120deg,#12A594,#1E9FC4)">'+q.k+'</span>'+
            '<span class="at"><b>'+esc(q.title)+'</b><span>'+esc(q.sub)+' · 点击回看</span></span>'+
            '<span class="arrow">播放</span></button>';
        }).join('')+
      '</div></div>'+
    '</div>'+
    '<div class="card"><div class="card-h"><h3>演示说明</h3></div><div class="card-b">'+
      '<p class="side-note">'+noteTxt+'演示由平台根据模型输出自动生成，用于把抽象的数学结果"可视化表达"。在论文中可作结果图使用，也可直接用于答辩展示。</p>'+
      '<div class="legend">'+
        (state.detailId==='p1'
          ? '<div><i style="background:#2EC4B0"></i>温度场结果（中心/表面升温、趋近烘房）</div>'+
            '<div><i style="background:#F5A623"></i>水分场结果（径向分布、Cmax 下降、阈值判定）</div>'+
            '<div><i style="background:#7FE0A8"></i>关键识别点（表面边界层、0.15 阈值线、半径收缩）</div>'
          : '<div><i style="background:#2EC4B0"></i>计划 / 预测侧结果（购电量、储能 SOC、电价）</div>'+
            '<div><i style="background:#F5A623"></i>实际 / 修正侧结果（真值、偏差结算）</div>'+
            '<div><i style="background:#7FE0A8"></i>关键识别点（触顶、缺口、滚动起点）</div>')+
      '</div>'+
      '<div style="margin-top:16px;padding-top:14px;border-top:1px solid var(--line-2)">'+
        '<p class="side-note" style="font-size:12.5px;color:#8A9AAD">每段演示时长 12 秒，支持逐帧回放；点击右上角可导出 HTML / GIF 用于答辩。</p>'+
      '</div>'+
    '</div></div>'+
  '</div>';
}
export function switchAnim(k){
  if(!animDone(k)){ pendAnim(k); return; }
  var st=$('stage'); if(st){ st.innerHTML=fsBtnHTML(k)+animShotHTML(k); }   /* 截图占位 + 全屏按钮 */
  ['0','1','2','3','4'].forEach(function(i){
    var el=$('q-'+i); if(el) el.classList.toggle('open',i===k);
  });
}

export let PY_CODE =
'# -*- coding: utf-8 -*-\n'+
'"""2026 国赛 C 题：微网与外部电网的电力调控策略\n'+
'   问题一 —— 确定信息下的单日 MILP 最优购电计划\n'+
'   数模演易 自动生成 · 可直接运行（需 pandas / pulp）\n'+
'"""\n'+
'import pandas as pd\n'+
'import pulp\n'+
'\n'+
'# ---------- 1. 储能与系统参数（附录1） ----------\n'+
'ETA   = 0.9               # 充放电效率\n'+
'S_INIT = 6000.0           # 0:00 储电量 (kWh)\n'+
'S_MIN, S_MAX = 1200.0, 10800.0\n'+
'P_MAX = 5000.0            # 最大充放功率 (kW)\n'+
'DT    = 10 / 60           # 时段长度 (h)\n'+
'C_MAX = P_MAX * DT        # 单时段最大充放电量 = 833.33 kWh\n'+
'\n'+
'# ---------- 2. 读取附件1：典型日电价 / 负载 / 光伏预测 ----------\n'+
'df = pd.read_excel("data/附件1.xlsx", sheet_name="预处理数据")\n'+
'price = df["电价_元每kWh"].values\n'+
'load  = df["小区负载电量"].values\n'+
'pv    = df["光伏发电量"].values\n'+
'T = len(df)                       # 144 个时段\n'+
'\n'+
'# ---------- 3. 建立混合整数线性规划 ----------\n'+
'prob = pulp.LpProblem("Microgrid_Day_Ahead", pulp.LpMinimize)\n'+
'g = pulp.LpVariable.dicts("buy", range(T), lowBound=0)\n'+
'c = pulp.LpVariable.dicts("chg", range(T), lowBound=0, upBound=C_MAX)\n'+
'd = pulp.LpVariable.dicts("dis", range(T), lowBound=0, upBound=C_MAX)\n'+
's = pulp.LpVariable.dicts("soc", range(T + 1), lowBound=S_MIN, upBound=S_MAX)\n'+
'u = pulp.LpVariable.dicts("flag", range(T), cat="Binary")   # 1=充电 0=放电\n'+
'\n'+
'# 目标：全天购电费用最小\n'+
'prob += pulp.lpSum(price[t] * g[t] for t in range(T))\n'+
'\n'+
'for t in range(T):\n'+
'    prob += g[t] + pv[t] + d[t] == load[t] + c[t]        # 功率平衡\n'+
'    prob += c[t] <= C_MAX * u[t]                         # 充放互斥\n'+
'    prob += d[t] <= C_MAX * (1 - u[t])\n'+
'    prob += s[t + 1] == s[t] + c[t] - d[t] / ETA         # 储能递推\n'+
'\n'+
'prob += s[0] == S_INIT\n'+
'prob += s[T] == S_INIT          # 24:00 必须回到 0:00 的荷电状态\n'+
'\n'+
'# ---------- 4. 求解 ----------\n'+
'prob.solve(pulp.PULP_CBC_CMD(msg=0))\n'+
'\n'+
'# ---------- 5. 结果汇总与导出 ----------\n'+
'buy_kwh = sum(g[t].value() for t in range(T))\n'+
'chg_kwh = sum(c[t].value() for t in range(T))\n'+
'dis_kwh = sum(d[t].value() for t in range(T))\n'+
'cost    = sum(price[t] * g[t].value() for t in range(T))\n'+
'\n'+
'print("求解状态   :", pulp.LpStatus[prob.status])\n'+
'print("全天购电量 : %.2f kWh" % buy_kwh)\n'+
'print("全天购电费 : %.2f 元" % cost)\n'+
'print("储能总充电 : %.2f kWh / 总放电 %.2f kWh" % (chg_kwh, dis_kwh))\n'+
'\n'+
'out = pd.DataFrame({\n'+
'    "时段": range(1, T + 1),\n'+
'    "电价": price,\n'+
'    "计划购电量": [g[t].value() for t in range(T)],\n'+
'    "充电量": [c[t].value() for t in range(T)],\n'+
'    "放电量": [d[t].value() for t in range(T)],\n'+
'    "时段末储电量": [s[t].value() for t in range(T)],\n'+
'})\n'+
'out.to_excel("result1.xlsx", index=False)\n'+
'print("已写出 result1.xlsx，共 %d 行" % len(out))\n';
/* E 题（p5 · SEM）main.py —— 问题一策略合理性诊断 */
export let PY_CODE_E =
'# -*- coding: utf-8 -*-\n'+
'"""2026 国赛 E 题：SEM 广告投放策略优化\n'+
'   问题一 —— 投放策略合理性诊断\n'+
'   数模演易 自动生成 · 可直接运行（需 pandas / numpy / matplotlib）\n'+
'"""\n'+
'import pandas as pd\n'+
'import numpy as np\n'+
'\n'+
'# ---------- 1. 读入主表（附件2 已与附件1/3 对齐） ----------\n'+
'df = pd.read_excel("data/附件2_预处理.xlsx")\n'+
'\n'+
'# ---------- 2. 效益指标 ----------\n'+
'agg = df.groupby("关键词ID").agg(\n'+
'    展现=("展现","sum"), 点击=("点击","sum"),\n'+
'    消费=("消费","sum"), 转化=("转化","sum")\n'+
').reset_index()\n'+
'agg["点击率"] = agg["点击"] / agg["展现"]\n'+
'agg["转化率"] = agg["转化"] / agg["点击"]\n'+
'agg["效益率"] = agg["转化"] / agg["消费"]\n'+
'\n'+
'# ---------- 3. 合理性诊断：头部是否拿到预算、尾部是否浪费 ----------\n'+
'agg = agg.sort_values("效益率", ascending=False)\n'+
'top, bot = agg.head(20), agg.tail(20)\n'+
'total = agg["消费"].sum()\n'+
'print(f"头部 20 词消费占比: {top[\'消费\'].sum()/total:.1%}")\n'+
'print(f"尾部 20 词消费占比: {bot[\'消费\'].sum()/total:.1%}")\n'+
'\n'+
'# ---------- 4. 时间与星期规律 ----------\n'+
'df["星期"] = pd.to_datetime(df["日期"]).dt.dayofweek\n'+
'df["时段"] = pd.to_datetime(df["时间"]).dt.hour\n'+
'hourly = df.groupby("时段").agg(转化=("转化","sum"), 消费=("消费","sum"))\n'+
'hourly["效益率"] = hourly["转化"] / hourly["消费"]\n'+
'print(f"效益双峰时段: {hourly[\'效益率\'].idxmax()}")\n'+
'\n'+
'# ---------- 5. 出结果 ----------\n'+
'agg.to_excel("result1.xlsx", index=False)\n'+
'print("已写出 result1.xlsx，共 %d 行" % len(agg))\n';
/* p1（2026 国赛 A 题 · 中药材热风烘干）main.py —— 问题一预热平衡有限体积求解 */
export let P1_PY_CODE =
'# -*- coding: utf-8 -*-\n'+
'"""2026 国赛 A 题：中药材热风烘干过程的热湿耦合建模\n'+
'   问题一 —— 预热平衡阶段（0-1800 s）温度 / 水分浓度演化\n'+
'   数模演易 自动生成 · 可直接运行（需 numpy）\n'+
'"""\n'+
'import numpy as np\n'+
'\n'+
'# ---------- 1. 物性参数（附录2，常物性） ----------\n'+
'R, T0, C0 = 0.02, 28.0, 2.55          # 半径(m) / 初温(°C) / 初始干基含水率\n'+
'RHO, CP, K = 820.0, 2600.0, 0.36      # 密度 / 比热 / 导热系数\n'+
'H, KM     = 25.0, 8e-7                # 对流换热 / 对流传质系数\n'+
'def D_of(C): return 7e-9*np.exp(-0.89*C)   # 扩散系数经验公式\n'+
'\n'+
'# ---------- 2. 烘房环境（附件1，300 s 采样后线性插值） ----------\n'+
't_a = np.arange(0, 14401, 300.0)\n'+
'T_a = np.array([28.0,30.834,33.202,35.524,37.882,39.595,41.513,42.745,\n'+
'    44.102,45.323,45.93,46.933,47.485,47.926,48.166,48.526,48.979,48.913,\n'+
'    49.116,49.492,49.743,49.739,49.683,49.941,49.852,49.845,49.714,49.664,\n'+
'    49.915,49.821,50.163,50.086,49.804,49.935,49.895,49.772,50.195,50.081,\n'+
'    50.218,49.84,50.139,49.865,50.097,50.119,49.9,50.212,49.82,50.152,50.165])\n'+
'C_a = np.array([0.01963,0.02191,0.02428,0.0266,0.02891,0.03098,0.03307,\n'+
'    0.03513,0.03694,0.03858,0.04011,0.04187,0.04272,0.04389,0.04483,0.04561,\n'+
'    0.04633,0.04707,0.04728,0.04765,0.04841,0.04871,0.04858,0.04908,0.04894,\n'+
'    0.04941,0.04928,0.04971,0.0498,0.04976,0.04981,0.05016,0.05021,0.04989,\n'+
'    0.05008,0.04982,0.04977,0.04987,0.05014,0.04981,0.04983,0.05024,0.05019,\n'+
'    0.05024,0.04982,0.05007,0.04999,0.04996,0.04986])\n'+
'def env(t):\n'+
'    return float(np.interp(t, t_a, T_a)), float(np.interp(t, t_a, C_a))\n'+
'\n'+
'# ---------- 3. 有限体积离散（21 节点，dr=0.1cm，显式 Euler） ----------\n'+
'N  = 21\n'+
'r  = np.linspace(0.0, R, N); dr = r[1]-r[0]; rm = 0.5*(r[1:]+r[:-1])\n'+
'T  = np.full(N, T0); C = np.full(N, C0)\n'+
'dt, t_end = 0.5, 1800.0\n'+
'snap = [100,300,600,900,1200,1500,1800]\n'+
'tab1, tab2 = {}, {}\n'+
'\n'+
'for s in range(int(t_end/dt)):\n'+
'    t = s*dt; Ta, Ca = env(t)\n'+
'    D   = D_of(C); Dif = 0.5*(D[1:]+D[:-1])\n'+
'    fl  = Dif*(C[1:]-C[:-1])/dr*rm                 # 界面扩散通量\n'+
'    dC  = np.zeros(N)\n'+
'    dC[1:-1] = (fl[1:]-fl[:-1])/(r[1:-1]*dr)       # 内部胞元\n'+
'    dC[0]    = fl[0]/(dr*dr/8.0)                   # 中心胞元(四分之一圆)\n'+
'    dC[-1]   = (-fl[-1]-KM*r[-1]*(C[-1]-Ca))/(r[-1]*dr)   # 表面:补给-蒸发\n'+
'    C = C + dt*dC\n'+
'    hf  = K*(T[1:]-T[:-1])/dr*rm                   # 界面导热通量\n'+
'    dT  = np.zeros(N)\n'+
'    dT[1:-1] = (hf[1:]-hf[:-1])/(r[1:-1]*dr*RHO*CP)\n'+
'    dT[0]    = hf[0]/(dr*dr/8.0*RHO*CP)\n'+
'    dT[-1]   = (-hf[-1]-H*r[-1]*(T[-1]-Ta))/(r[-1]*dr*RHO*CP)\n'+
'    T = T + dt*dT\n'+
'    tt = t + dt\n'+
'    if tt in snap:\n'+
'        pos = [0.0,0.005,0.01,0.015,0.02]\n'+
'        tab1[tt] = [round(float(np.interp(p,r,T)),4) for p in pos]\n'+
'        tab2[tt] = [round(float(np.interp(p,r,C)),4) for p in pos]\n'+
'\n'+
'# ---------- 4. 输出表1 / 表2 ----------\n'+
'print("=" * 60)\n'+
'print("2026 国赛 A 题 · 问题一 · 预热平衡阶段（30 min）")\n'+
'print("=" * 60)\n'+
'print("表1 温度(°C)    距中心: 0 / 0.5 / 1 / 1.5 / 2 cm")\n'+
'for k in snap: print("  %4d s: %s" % (k, tab1[k]))\n'+
'print("表2 水分浓度(kg/kg)")\n'+
'for k in snap: print("  %4d s: %s" % (k, tab2[k]))\n'+
'print("=" * 60)\n'+
'print("结论：预热段热响应快（全场升温 5~8.6°C），湿响应慢")\n'+
'print("      （仅表面 0.1cm 薄层失水 2.55→1.31，内部不变）")\n';
export let PY_CODE_CUR = PY_CODE;

export let RUN_OUT =
'[1/5] 读取 data/附件1.xlsx .................. 144 行 × 4 列（10 min 粒度）\n'+
'[2/5] 储能参数: 容量 12000 kWh, 功率 5000 kW, η=0.9, S∈[1200, 10800]\n'+
'[3/5] 构建 MILP: 576 连续变量 + 144 整数变量 + 864 条约束\n'+
'[4/5] CBC 求解 .............................. Status: Optimal\n'+
'      全天购电量 : 59482.70 kWh\n'+
'      全天购电费 : 35126.95 元\n'+
'      储能总充电 : 20740.67 kWh / 总放电 16799.94 kWh\n'+
'      S(0:00) = 6000.00 kWh, S(24:00) = 6000.00 kWh  ✓ 递推闭合\n'+
'[5/5] 已写出 result1.xlsx，共 144 行（含逐时段 SOC 与充放电）\n'+
'完成，总耗时 4.18 s';
/* E 题（p5 · SEM）运行输出 */
export let RUN_OUT_E =
'[1/5] 读取 data/附件2_预处理.xlsx ......... 7726 推广单元 × 全年日志\n'+
'[2/5] 效益指标: 点击率 / 转化率 / 效益率 ............ 已计算\n'+
'[3/5] 头部 20 词消费占比: 41.3%\n'+
'      尾部 20 词消费占比: 6.8%\n'+
'[4/5] 效益双峰时段: 10 时（上午峰） / 21 时（晚间峰）\n'+
'      诊断: 头部集中度偏低、尾部仍在浪费\n'+
'[5/5] 已写出 result1.xlsx，共 7726 行（含效益指标四列）\n'+
'完成，总耗时 3.42 s';
/* p1（2026 国赛 A 题 · 中药材热风烘干）运行输出 */
export let P1_RUN_OUT =
'[1/6] 读取附录2参数: ρ=820, cp=2600, k=0.36, h=25, km=8e-7, D=7e-9·exp(-0.89C)\n'+
'[2/6] 读取附件1烘房环境: 241 点（0-14400 s），T 28→50.25°C / C 0.0196→0.0503\n'+
'[3/6] 有限体积离散: 21 节点（dr=0.1cm），中心胞元 dr²/8，显式 Euler dt=0.5s\n'+
'[4/6] 预热平衡模拟 1800 s .................. 收敛（能量守恒误差 < 1e-6）\n'+
'      1800 s 中心温度 : 33.4188 °C\n'+
'      1800 s 表面温度 : 36.5905 °C\n'+
'      1800 s 表面水分 : 1.3120 kg/kg（内部仍为 2.55，边界层约 0.1 cm）\n'+
'[5/6] 输出表1/表2（100~1800 s × 0~2 cm，保留四位小数）\n'+
'[6/6] 已写出 result1.xlsx（1801 行 × 21 列，温度 + 水分浓度两个工作表）\n'+
'完成，总耗时 2.31 s';
export let RUN_OUT_CUR = RUN_OUT;

export function renderCodePanel(){
  return '<div class="card" style="margin-bottom:16px"><div class="card-h">'+
      ICON.code+'<h3>建模主程序 main.py</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'代码已复制到剪贴板\')">复制代码</button>'+
      '<button class="btn-ghost" onclick="toast(\'下载为演示功能\')">下载 .py</button>'+
    '</div><div class="card-b" style="padding:0">'+
      '<div class="code-box">'+
        '<div class="code-top"><span class="f">main.py · Python 3.10</span><div class="grow"></div>'+
          '<button onclick="toast(\'代码已复制到剪贴板\')">复制</button>'+
          '<button onclick="runCode(this)">运行</button></div>'+
        '<pre class="code">'+hl(PY_CODE_CUR)+'</pre>'+
        '<div class="run-out"><span class="lbl">RUN OUTPUT</span><span id="run-txt">$</span></div>'+
      '</div>'+
    '</div></div>'+
    '<div class="card"><div class="card-h"><h3>依赖与运行说明</h3></div><div class="card-b">'+
      '<p style="font-size:13.5px;color:#4A5A70;line-height:1.9">'+(state.detailId==='p5'
        ? '依赖：<span class="mono">pandas</span>、<span class="mono">numpy</span>、<span class="mono">scipy</span>（CVaR 求解）。数据文件请放在 <span class="mono" style="background:#EAF5F5;padding:2px 6px;border-radius:5px">data/</span> 目录，结果输出至 <span class="mono" style="background:#EAF5F5;padding:2px 6px;border-radius:5px">result1.xlsx</span>。问题二至问题四在同一骨架上增加分类 / 再分配 / CVaR 模块即可。代码可以在「工作台」里直接运行，结果会即时展现在对话中。'
        : (state.detailId==='p1'
          ? '依赖：<span class="mono">numpy</span>（有限体积法双场求解，纯标准库即可离线运行）。问题二至问题四在同一骨架上增加热湿耦合 / 时长搜索 / 动边界模块即可。代码可以在「工作台」里直接运行，结果会即时展现在对话中。'
          : '依赖：<span class="mono">pandas</span>、<span class="mono">pulp</span>（自带 CBC 求解器，无需商业授权）。数据文件请放在 <span class="mono" style="background:#EAF5F5;padding:2px 6px;border-radius:5px">data/</span> 目录，结果输出至 <span class="mono" style="background:#EAF5F5;padding:2px 6px;border-radius:5px">result1.xlsx</span>。问题二至问题四在同一骨架上增加预测模块与滚动循环即可。代码可以在「工作台」里直接运行，结果会即时展现在对话中。'))+'</p>'+
    '</div></div>';
}
export function runCode(btn){
  var out=$('run-txt'); if(!out) return;
  if(btn) btn.textContent='运行中...';
  out.textContent='$ ';
  var lines=RUN_OUT_CUR.split('\n'), i=0;
  (function tick(){
    if(i<lines.length){
      out.textContent+=lines[i]+'\n';
      i++; setTimeout(tick,110);
    }else{ if(btn) btn.textContent='运行'; toast('运行完成，结果已同步'); }
  })();
}

/* ================= 成果结构 =================
   「我的成果」页与平台已生成案例（题目详情页）共用同一套展示结构：
     成果
     ├── 论文初稿
     └── 支撑材料
         ├── 源代码（全部代码）
         ├── 图片（可视化图表等）
         └── 文件（数据预处理后的文件等）
   =========================================== */
export let SUPPORT ={
  code:[
    ['main.py','问题一 · 单日 MILP · Python 3.10（PuLP/CBC）','78 行'],
    ['model_q2.py','问题二 · 随机森林预测 + 滚动 LP','214 行'],
    ['model_q3.py','问题三 · 多阶段滚动修正 + 双向结算','268 行'],
    ['model_q4.py','问题四 · 波动电价下的随机规划','302 行'],
    ['preprocess.py','数据预处理 · 日历对齐 / 缺测插值 / 星期分层','136 行'],
    ['plot_result.py','结果可视化 · 全部图表 300 dpi 出图','92 行']
  ],
  image:[
    ['fig1_典型日负载与光伏.png','春分 / 夏至 / 秋分 / 冬至四组曲线','300 dpi'],
    ['fig2_全年日电量热力图.png','365 天 × 144 时段','300 dpi'],
    ['fig3_储能SOC与分时电价.png','问题一三联图：购电 / SOC / 电价','300 dpi'],
    ['fig4_全年费用累计曲线.png','计划费用 vs 实际费用','300 dpi'],
    ['fig5_安全裕量U型曲线.png','α 网格搜索灵敏度','300 dpi'],
    ['fig6_四问结果对照.png','四问费用柱状对比','300 dpi']
  ],
  file:[
    ['附件1_预处理.xlsx','典型日 144 时段 · 已日历对齐','预处理后'],
    ['附件2_预处理.xlsx','全年 365 × 144 真值 · 已插值','预处理后'],
    ['附件3_预报整理.xlsx','0:00 / 6:00 / 12:00 / 18:00 四时刻预报','预处理后'],
    ['附件4_分时电价.xlsx','全年分时电价表','预处理后'],
    ['result1.xlsx','问题一 · 144 行逐时段明细','结果'],
    ['result2.xlsx','问题二 · 全年滚动结果','结果'],
    ['result3.xlsx','问题三 · 滚动修正结果','结果'],
    ['result4-2.xlsx','问题四-2 · 波动电价重算问题二','结果'],
    ['result4-3.xlsx','问题四-3 · 波动电价重算问题三','结果']
  ]
};
/* E 题（p5 · SEM）支撑材料 —— 与 SUPPORT 同构，按题切换 */
export let SUPPORT_E ={
  code:[
    ['main.py','问题一 · 策略合理性诊断 · Python 3.10','86 行'],
    ['classify.py','问题二 · 四象限分类 + 阈值稳健性','198 行'],
    ['allocate.py','问题三 · 单元级投放 + 预算再分配（KKT）','242 行'],
    ['uncertain.py','问题四 · CVaR 约束 + 六项指标区间','286 行'],
    ['preprocess.py','数据预处理 · 主表拼装 / 缺测插值 / 星期分层','142 行'],
    ['plot_result.py','结果可视化 · 全部图表 300 dpi 出图','98 行']
  ],
  image:[
    ['fig1_全年日效益热力图.png','365 天 × 效益率','300 dpi'],
    ['fig2_星期时段效益矩阵.png','7 × 24 双峰结构','300 dpi'],
    ['fig3_四象限分类散点.png','消费 vs 转化','300 dpi'],
    ['fig4_单元级预算分配.png','12 推广单元再分配','300 dpi'],
    ['fig5_阈值U型曲线.png','±10% 扰动稳健性','300 dpi'],
    ['fig6_六项指标区间.png','CVaR 90% / 95% 对照','300 dpi']
  ],
  file:[
    ['附件1_预处理.xlsx','推广单元 × 关键词基础信息 · 已对齐','预处理后'],
    ['附件2_预处理.xlsx','全年逐日投放日志 · 已插值','预处理后'],
    ['附件3_预处理.xlsx','关键词分类指标 · 已标定','预处理后'],
    ['result1.xlsx','问题一 · 策略诊断明细','结果'],
    ['result2.xlsx','问题二 · 四象限分类结果','结果'],
    ['result3.xlsx','问题三 · 单元级逐日投放明细','结果'],
    ['result4.xlsx','问题四 · 六项指标期望区间','结果']
  ]
};
/* p1（2026 国赛 A 题 · 中药材热风烘干）支撑材料 —— 与 SUPPORT 同构，按题切换 */
export let P1_SUPPORT ={
  code:[
    ['main.py','问题一 · 预热平衡有限体积求解 · Python 3.10（numpy）','86 行'],
    ['model_q2.py','问题二 · 全过程变物性双场耦合','212 行'],
    ['model_q3.py','问题三 · Cmax 阈值搜索 + 表5 输出','176 行'],
    ['model_q4.py','问题四 · Landau 动边界 FV + 对流项','288 行'],
    ['fv_core.py','有限体积核心：网格 / 通量 / 边界条件','142 行'],
    ['plot_result.py','结果可视化 · 径向分布 / 演化曲线 / 收缩对比','96 行']
  ],
  image:[
    ['fig1_圆柱药材与FV网格.png','21 节点径向一维剖分示意','300 dpi'],
    ['fig2_预热平衡温度场.png','表1 时空演化（100~1800 s）','300 dpi'],
    ['fig3_预热平衡水分边界层.png','表2 表面 0.1cm 薄层失水','300 dpi'],
    ['fig4_全过程双场演化.png','问题二 温度/水分耦合曲线','300 dpi'],
    ['fig5_烘干时长判定.png','Cmax(t) 与 0.15 阈值线交点','300 dpi'],
    ['fig6_动边界收缩对比.png','问题四 固定/动边界 Cmax 对比','300 dpi']
  ],
  file:[
    ['附件1_烘房环境.xlsx','241 点 温度/水分浓度（0-14400 s）','原始数据'],
    ['附件2_半径收缩.xlsx','145 点 半径 2→1.198 cm','原始数据'],
    ['result1.xlsx','问题一 · 1801×21 温度+水分浓度','结果'],
    ['result2.xlsx','问题二 · 全过程逐秒明细','结果'],
    ['result3.xlsx','问题三 · Cmax 曲线 + 表5','结果'],
    ['result4.xlsx','问题四 · 动边界结果 + 表6','结果']
  ]
};
export let SUPPORT_CUR = SUPPORT;
export let SUP_GROUPS =[
  ['code','源代码','全部代码文件，与论文中的模型一一对应，可直接复现四个问题的结果'],
  ['image','图片','论文与答辩用到的可视化图表，均为 300 dpi，可直接插入论文'],
  ['file','文件','数据预处理后的输入文件，以及各问运行得到的结果文件']
];
export let SUP_COLOR ={code:'linear-gradient(120deg,#0B2E36,#1E6FA8)',image:'linear-gradient(120deg,#17A2C4,#2EC4B0)',file:'linear-gradient(120deg,#2BA471,#12A594)'};
export let SUP_ICON ={
  code:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 18l6-6-6-6"/><path d="M8 6l-6 6 6 6"/></svg>',
  image:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.6"/><path d="M21 15l-5-5L5 21"/></svg>',
  file:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'
};
export function supRow(g,f){
  return '<button class="acard" onclick="toast(\''+f[0]+' 下载为演示功能\')">'+
    '<span class="ai" style="background:'+SUP_COLOR[g]+'">'+SUP_ICON[g]+'</span>'+
    '<span class="at"><b>'+esc(f[0])+'</b><span>'+esc(f[1])+'</span></span>'+
    '<span class="tag-c" style="font-size:11px">'+esc(f[2])+'</span>'+
    '<span class="arrow">下载</span></button>';
}
export function supportHTML(){
  return SUP_GROUPS.map(function(g){
    var list=SUPPORT_CUR[g[0]];
    return '<div class="card" style="margin-bottom:14px"><div class="card-h">'+
        SUP_ICON[g[0]].replace(/#fff/g,'#12A594')+'<h3>'+g[1]+'</h3>'+
        '<span class="tag-c" style="font-size:11px">'+list.length+' 项</span><div class="grow"></div>'+
        '<button class="btn-ghost" onclick="toast(\''+g[1]+'打包下载为演示功能\')">打包下载</button></div>'+
      '<div class="card-b"><p class="side-note" style="margin-bottom:12px">'+g[2]+'</p>'+
        '<div class="arts">'+list.map(function(f){return supRow(g[0],f);}).join('')+'</div>'+
      '</div></div>';
  }).join('');
}
export function paperDeliverHTML(){
  if(state.detailId==='p1') return P1_paperDeliverHTML();
  if(state.detailId==='p5') return '<div class="card"><div class="card-h">'+ART.paper.icon.replace('#fff','#12A594')+'<h3>论文初稿 · v1</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'下载为演示功能\')">下载 .docx</button>'+
      '<button class="btn-ghost" onclick="toast(\'PDF 下载为演示功能\')">PDF</button></div>'+
    '<div class="card-b"><div class="doc" style="padding:0">'+
    '<h2>SEM 广告投放策略优化研究</h2>'+
    '<div class="dc">22 页 · 图 15 张 · 表 8 张 · 参考文献 12 篇</div>'+
    '<h3>摘要</h3>'+
    '<p>针对搜索引擎营销（SEM）广告投放的选词与预算决策问题，本文以某公司 2025 年全年投放日志为基础，构建"效益指标 — 饱和响应 — 单元级再分配"的统一建模框架。问题一通过效益率排序诊断原策略，发现头部 20 词消费占比 41.3%、尾部 20 词占比 6.8%，头部集中度偏低、尾部仍在浪费。问题二以消费为横轴、转化为纵轴，用双阈值把关键词分为黄金 / 重点 / 潜力 / 问题四类，并在 ±10% 扰动下成员变动不超过 8%。问题三在各单元饱和响应函数 f(B)=A·B/(B+τ) 的基础上，由 KKT 条件导出"边际效益相等"的最优性条件，做预算再分配，总期望转化相对原策略提升。问题四把 A_u、τ_u 视为随机变量，用 CVaR 约束给出总展现 / 总点击 / 总消费 / 总转化 / 效益率 / 紧急购流量级六项指标在 90% 与 95% 置信水平下的期望区间，并发现尾部风险主要来自效益端。</p>'+
    '<h3>目录</h3>'+
    '<ul><li>一、问题重述与信息结构分析</li><li>二、数据预处理与探索性分析</li><li>三、问题一：投放策略合理性诊断</li><li>四、问题二：关键词四象限分类</li><li>五、问题三：单元级投放与预算再分配</li><li>六、问题四：不确定性下的 CVaR 决策</li><li>七、灵敏度分析与稳健性检验</li><li>八、结论与改进方向</li><li>附录：代码与结果文件说明</li></ul>'+
    '<h3>第三章（节选）</h3>'+
    '<div class="formula">f(B) = A · B / (B + τ)　　f′(B) = A · τ / (B + τ)²　　KKT: A_u · τ_u / (B_u + τ_u)² = λ</div>'+
    '<p>考虑到线性响应会高估高出价段流量，本文采用饱和型函数刻画"边际递减"，并配合半饱和出价 τ_u 拟合各单元曲线；在预算上限约束下，由 KKT 条件可解析地给出各单元边际效益相等的再分配方案，避免启发式……</p>'+
    '<div class="note">论文可继续在平台上与 AI 交互修改：改摘要、补灵敏度、调排版、加图表说明都可以。</div>'+
    '</div></div></div>'+
    '<div class="card"><div class="card-b"><div class="dl-row" style="margin-top:0">'+
      '<button class="dl p" onclick="toast(\'下载为演示功能\')">下载论文初稿</button>'+
      '<button class="dl" onclick="toast(\'论文分享链接已复制\')">复制分享链接</button>'+
    '</div></div></div>';
  return '<div class="card"><div class="card-h">'+ART.paper.icon.replace('#fff','#12A594')+'<h3>论文初稿 · v1</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'下载为演示功能\')">下载 .docx</button>'+
      '<button class="btn-ghost" onclick="toast(\'PDF 下载为演示功能\')">PDF</button></div>'+
    '<div class="card-b"><div class="doc" style="padding:0">'+
    '<h2>微网与外部电网电力调控策略研究</h2>'+
    '<div class="dc">22 页 · 图 15 张 · 表 8 张 · 参考文献 12 篇</div>'+
    '<h3>摘要</h3>'+
    '<p>针对小区微网在外部电网实时电价下的购电决策问题，本文以 2025 年逐日用能数据为基础，构建"预测 — 计划 — 滚动修正"的统一建模框架。首先建立含充放电互斥约束的混合整数线性规划模型，得到确定信息下的全天最优购电策略：总购电量 59482.70 kWh、总购电费 35126.95 元。进而引入随机森林负荷预测与三日滑动光伏预测，设计带安全裕量系数 α 的两阶段滚动线性规划，全年（334 天报告区间）总购电费降至 1369.18 万元，紧急购电占比 6.0%。进一步考虑每日四次光伏预报，建立带调整触发阈值 θ 的多阶段滚动修正模型，并刻画双向违约结算规则，全年总费用 1535.07 万元，紧急购电占比进一步降至 1.31%。最后在外网电价实时波动情形下重算，得到 1471.70 万元与 1787.47 万元两组结果，并发现安全裕量的最优取值随结算规则变化而发生反转。</p>'+
    '<h3>目录</h3>'+
    '<ul><li>一、问题重述与信息结构分析</li><li>二、数据预处理与探索性分析</li><li>三、问题一：确定信息下的单日 MILP 模型</li><li>四、问题二：预测驱动的两阶段滚动线性规划</li><li>五、问题三：四次预报下的多阶段滚动修正模型</li><li>六、问题四：电价不确定情形下的模型改造</li><li>七、灵敏度分析与稳健性检验</li><li>八、结论与改进方向</li><li>附录：代码与结果文件说明</li></ul>'+
    '<h3>第三章（节选）</h3>'+
    '<div class="formula">min　Σ_{t=1}^{144} p_t · g_t　　s.t.　g_t + PV_t + d_t = L_t + c_t,\nS_{t+1} = S_t + c_t − d_t / η,　1200 ≤ S_t ≤ 10800,　S_0 = S_144 = 6000</div>'+
    '<p>考虑到若不加充放电互斥约束，求解器会得到同一时段既充又放的假最优解（能量在 η ≠ 1 时被凭空放大），本文引入 0-1 变量 u_t，并配合单时段上限 833.33 kWh 建立紧的上界，保证所得解为全局最优……</p>'+
    '<div class="note">论文可继续在平台上与 AI 交互修改：改摘要、补灵敏度、调排版、加图表说明都可以。</div>'+
    '</div></div></div>'+
    '<div class="card"><div class="card-b"><div class="dl-row" style="margin-top:0">'+
      '<button class="dl p" onclick="toast(\'下载为演示功能\')">下载论文初稿</button>'+
      '<button class="dl" onclick="toast(\'论文分享链接已复制\')">复制分享链接</button>'+
    '</div></div></div>';
}
/* p1（2026 国赛 A 题 · 中药材热风烘干）论文初稿 */
export function P1_paperDeliverHTML(){
  return '<div class="card"><div class="card-h">'+ART.paper.icon.replace('#fff','#12A594')+'<h3>论文初稿 · v1</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'下载为演示功能\')">下载 .docx</button>'+
      '<button class="btn-ghost" onclick="toast(\'PDF 下载为演示功能\')">PDF</button></div>'+
    '<div class="card-b"><div class="doc" style="padding:0">'+
    '<h2>中药材热风烘干过程的热湿耦合建模与烘干时长确定</h2>'+
    '<div class="dc">24 页 · 图 14 张 · 表 6 张 · 参考文献 11 篇</div>'+
    '<h3>摘要</h3>'+
    '<p>针对圆柱形中药材（长 25 cm、半径 2 cm）热风烘干的两阶段调控问题，本文建立径向一维热湿耦合偏微分方程组，以有限体积法（21 节点、显式 Euler）统一求解。问题一在附录2 常物性下模拟预热平衡段：1800 s 内药材中心温度升至 33.4188°C、表面 36.5905°C，表面水分降至 1.3120 kg/kg 而内部仍为 2.55，呈现"热快湿慢"特征。问题二换用附录3 变物性模拟全过程，3 h 时中心温度 49.81°C 趋近烘房，中心/表面水分分别为 2.19/1.01 kg/kg。问题三以"各处水分低于 0.15 kg/kg"为判据，由 Cmax(t) 阈值搜索得烘干时长 16.87 h。问题四引入 Landau 坐标将半径收缩（2→1.198 cm）映射到固定域并计入对流泵送项，得烘干时长 21.49 h——附录4 扩散系数比附录3 低约 5 倍，物性放慢主导、几何收缩次要。灵敏度分析表明扩散系数为第一敏感参数，对流换热系数仅影响预热段。</p>'+
    '<h3>目录</h3>'+
    '<ul><li>一、问题重述与信息结构</li><li>二、机理分析与控制方程</li><li>三、数值方法：有限体积离散</li><li>四、问题一：预热平衡阶段模拟</li><li>五、问题二：全过程热湿耦合模拟</li><li>六、问题三：烘干时长判定</li><li>七、问题四：动边界收缩模型</li><li>八、灵敏度分析与稳健性检验</li><li>九、结论与改进方向</li><li>附录：代码与结果文件说明</li></ul>'+
    '<h3>第二章（节选）</h3>'+
    '<div class="formula">∂C/∂t = (1/r)·∂/∂r( r·D(C,T)·∂C/∂r )　　r ∈ [0, R]\nρ(C)·cp(C)·∂T/∂t = (1/r)·∂/∂r( r·k(C)·∂T/∂r )\n边界：-D·∂C/∂r|R = km·(C|R−Ca(t))，　-k·∂T/∂r|R = h·(T|R−Ta(t))，　∂/∂r|0 = 0</div>'+
    '<p>考虑到长径比 12.5 ≫ 1，轴向温度梯度相比径向可忽略（端面贡献约 4%），本文将三维问题简化为径向一维柱坐标模型。离散时中心胞元取四分之一圆（体积 dr²/8），表面半胞元的净通量为内部扩散补给减去表面对流传质，两类胞元的守恒形式保证全程质量/能量闭合……</p>'+
    '<div class="note">论文可继续在平台上与 AI 交互修改：改摘要、补灵敏度、调排版、加图表说明都可以。</div>'+
    '</div></div></div>'+
    '<div class="card"><div class="card-b"><div class="dl-row" style="margin-top:0">'+
      '<button class="dl p" onclick="toast(\'下载为演示功能\')">下载论文初稿</button>'+
      '<button class="dl" onclick="toast(\'论文分享链接已复制\')">复制分享链接</button>'+
    '</div></div></div>';
}
/* 成果导航：论文初稿 / 支撑材料 */
export function dlvNavHTML(cur,setter){
  var sup=(cur!=='paper');
  var meta = sup
    ? '源代码 '+SUPPORT_CUR.code.length+' 项 · 图片 '+SUPPORT_CUR.image.length+' 项 · 文件 '+SUPPORT_CUR.file.length+' 项'
    : '22 页 · 图 15 张 · 表 8 张';
  return '<div class="mini-tabs">'+
    '<button class="mini-tab'+(cur==='paper'?' on':'')+'" onclick="'+setter+'\'paper\')">论文初稿</button>'+
    '<button class="mini-tab'+(sup?' on':'')+'" onclick="'+setter+'\'code\')">支撑材料</button>'+
    '<div style="flex:1"></div>'+
    '<span style="align-self:center;font-size:12.5px;color:#8A9AAD">'+meta+'</span>'+
  '</div>';
}
export function dlvHTML(cur,setter){
  return dlvNavHTML(cur,setter)+(cur==='paper'?paperDeliverHTML():supportHTML());
}
export let dlvTab ='paper';
export function setDlvTab(t){
  dlvTab=t;
  var w=$('dlv-wrap');
  if(w) w.innerHTML=dlvHTML(t,'setDlvTab(');
}

function downloadResultBlob(blob,name){
  saveAs(blob,name);
}
function resultPaperElement(){
  var root=document.querySelector('#unified-result-content .doc, #unified-result-content .result-source');
  return root as HTMLElement|null;
}
function downloadPaperPdf(){
  var root=resultPaperElement();
  if(!root){toast('暂无论文初稿可下载');return;}
  html2pdf().set({margin:10,filename:(state.detailId||'case')+'_论文初稿.pdf',image:{type:'jpeg',quality:.96},html2canvas:{scale:1.5,useCORS:true},jsPDF:{unit:'mm',format:'a4',orientation:'portrait'}}).from(root).save();
}
/* 最小可用的 .docx 生成器（替代 html-docx-js，2026-10-19）
   只做一件事：把论文初稿的 HTML 文本按段落塞进 WordprocessingML。
   产物是标准 docx 包（[Content_Types].xml + _rels/.rels + word/document.xml），Word/WPS 都能打开。 */
async function htmlToDocxBlob(html){
  var text=String(html||'')
    .replace(/^[\s\S]*?<body[^>]*>/i,'')
    .replace(/<\/body>[\s\S]*$/i,'')
    .replace(/<br\s*\/?>/gi,'\n')
    .replace(/<\/(h1|h2|h3|h4|p|div|li|tr)>/gi,'\n')
    .replace(/<[^>]+>/g,'')
    .replace(/&nbsp;/g,' ')
    .replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&quot;/g,'"').replace(/&#39;/g,"'");
  var esc=function(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');};
  var paras=text.split(/\n+/).map(function(t){return t.replace(/\s+$/,'');}).filter(function(t){return t.replace(/\s/g,'')!=='';});
  var xml=paras.map(function(t){
    return '<w:p><w:pPr><w:rPr><w:rFonts w:ascii="Arial" w:eastAsia="Microsoft YaHei"/><w:sz w:val="21"/></w:rPr></w:pPr>'+
           '<w:r><w:t xml:space="preserve">'+esc(t)+'</w:t></w:r></w:p>';
  }).join('');
  var zip=new JSZip();
  zip.file('[Content_Types].xml','<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'+
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'+
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'+
    '<Default Extension="xml" ContentType="application/xml"/>'+
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'+
    '</Types>');
  zip.folder('_rels').file('.rels','<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'+
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'+
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'+
    '</Relationships>');
  zip.folder('word').file('document.xml','<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'+
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'+xml+
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>'+
    '</w:body></w:document>');
  return await zip.generateAsync({type:'blob'});
}
function downloadPaperWord(){
  var root=resultPaperElement();
  if(!root){toast('暂无论文初稿可下载');return;}
  var html='<!DOCTYPE html><html><head><meta charset="utf-8"><style>body{font-family:Arial,"Microsoft YaHei",sans-serif;line-height:1.7}h2{text-align:center}h3{margin-top:24px}.formula{font-family:monospace;white-space:pre-wrap}</style></head><body>'+root.innerHTML+'</body></html>';
  htmlToDocxBlob(html).then(function(blob){
    downloadResultBlob(blob,(state.detailId||'case')+'_论文初稿.docx');
  }).catch(function(){toast('Word 导出失败，请稍后重试');});
}
function supportEntries(){
  var entries=[];
  if(state.detailId!=='p1'&&state.detailId!=='p3'&&state.detailId!=='p5') return entries;
  ['code','image','file'].forEach(function(kind){
    var list=SUPPORT_CUR[kind]||[];
    list.forEach(function(file){entries.push({kind:kind,name:file[0],description:file[1],state:file[2]});});
  });
  return entries;
}
async function downloadSupportZip(){
  var entries=supportEntries();
  if(!entries.length){toast('暂无支撑材料可下载');return;}
  var zip=new JSZip();
  var folders={code:'01_源代码',file:'02_数据结果',image:'03_可视化图表'};
  entries.forEach(function(file){
    var safe=String(file.name||'支撑材料').replace(/[\\/:*?"<>|]/g,'_');
    var content='文件名：'+file.name+'\n说明：'+(file.description||'')+'\n状态：'+(file.state||'Mock 占位文件')+'\n';
    zip.file(folders[file.kind]+'/'+safe+'.txt',content);
  });
  downloadResultBlob(await zip.generateAsync({type:'blob'}),(state.detailId||'case')+'_支撑材料.zip');
}
function downloadIcon(){return '<svg class="result-download-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/></svg>';}
function renderUnifiedResultPanel(p,managed){
  var resultText='';
  if(managed){
    try{
      var files=JSON.parse(managed.resultFilesJson||'[]');
      if(Array.isArray(files)&&files.length) resultText=files[0].text||'';
    }catch(e){}
    resultText=resultText||managed.resultHtml||'';
  }
  var builtIn=state.detailId==='p1'||state.detailId==='p3'||state.detailId==='p5';
  var paper= builtIn ? paperDeliverHTML() : (resultText ? '<div class="result-source">'+resultText+'</div>' : '<div class="result-empty">暂无论文初稿</div>');
  var support= builtIn ? supportHTML() : '<div class="support-empty"><h3>支撑材料</h3><div class="support-placeholder"><div>代码</div><div>预处理数据</div><div>result 数据</div><div>可视化图</div></div></div>';
  var actions=dlvTab==='paper'
    ? '<div class="result-actions"><button type="button" class="result-action" onclick="downloadPaperPdf()">'+downloadIcon()+'下载 PDF</button><button type="button" class="result-action" onclick="downloadPaperWord()">'+downloadIcon()+'下载 Word</button></div>'
    : '<div class="result-actions"><button type="button" class="result-action result-action-primary" onclick="downloadSupportZip()">'+downloadIcon()+'打包下载支撑材料</button></div>';
  return '<div class="result-shell"><h2 class="result-title">成果 · 论文初稿与支撑材料</h2><div class="result-tabs" role="tablist">'+
    '<button type="button" class="result-tab'+(dlvTab==='paper'?' is-active':'')+'" role="tab" aria-selected="'+(dlvTab==='paper'?'true':'false')+'" onclick="setUnifiedResultTab(\'paper\')">论文初稿</button>'+
    '<button type="button" class="result-tab'+(dlvTab!=='paper'?' is-active':'')+'" role="tab" aria-selected="'+(dlvTab!=='paper'?'true':'false')+'" onclick="setUnifiedResultTab(\'support\')">支撑材料</button></div>'+
    '<div class="result-content" id="unified-result-content"><div class="result-content-toolbar">'+actions+'</div>'+(dlvTab==='paper'?paper:support)+'</div></div>';
}
export function setUnifiedResultTab(tab){
  dlvTab=tab;
  var panel=$('panel-result');
  var p=PROBLEMS.filter(function(x){return x.id===state.detailId})[0]||{id:state.detailId,title:'',tag:'',cat:''};
  panel.innerHTML=renderUnifiedResultPanel(p,outsourcedCase(state.detailId));
}

export function renderResultPanel(p){
  return renderUnifiedResultPanel(p,null);
  if(state.detailId==='p1') return '<div class="kpis">'+
      '<div class="kpi"><div class="k">问题一 · 1800 s 表面温度</div><div class="v">36.59</div><div class="d">°C · 中心 33.42，预热平衡</div></div>'+
      '<div class="kpi"><div class="k">问题二 · 3 h 表面水分</div><div class="v">1.01</div><div class="d">kg/kg · 中心 2.19，全过程</div></div>'+
      '<div class="kpi"><div class="k">问题三 · 烘干时长</div><div class="v">16.87</div><div class="d">h · 固定半径，Cmax=0.15</div></div>'+
      '<div class="kpi"><div class="k">问题四 · 烘干时长</div><div class="v">21.49</div><div class="d">h · 动边界，R 收缩至 1.21 cm</div></div>'+
    '</div>'+
    '<h3 style="font-size:15px;margin:6px 0 12px">成果 · 论文初稿与支撑材料</h3>'+
    '<div id="dlv-wrap">'+dlvHTML(dlvTab,'setDlvTab(')+'</div>'+
    '<div style="height:16px"></div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>四问结果对照</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'导出为演示功能\')">导出 Excel</button></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr>'+
      '<th>问题</th><th>建模要点</th><th>核心方法</th><th>关键参数</th><th>结果</th></tr></thead><tbody>'+
      [['问题一','预热平衡 0-1800 s · 常物性','径向一维 FV · Robin 边界','h=25, km=8e-7, D=7e-9·e^(−0.89C)','表面温度 36.59°C · 表面水分 1.312'],
       ['问题二','全过程 2-3 天 · 变物性','FV + 附录3 经验公式','D=2.4e-3·e^(−0.45C)·e^(−3850/T)','3h 中心 2.19 / 表面 1.01 kg/kg'],
       ['问题三','各处水分 &lt;0.15 判据','Cmax(t) 阈值搜索','阈值 0.15 kg/kg','烘干时长 16.87 h'],
       ['问题四','动边界 · 半径收缩','Landau 坐标 FV + 对流项','附件2 R: 2→1.198 cm · 附录4','烘干时长 21.49 h · R 终 1.207 cm']].map(function(r){
        return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td><td>'+r[4]+'</td></tr>';
      }).join('')+
      '</tbody></table></div></div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>表5 · 药材烘干过程的水分浓度（问题三，固定半径）</h3></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr>'+
      '<th>时间/h</th><th>0 cm</th><th>0.5 cm</th><th>1 cm</th><th>1.5 cm</th><th>2 cm（表面）</th></tr></thead><tbody>'+
      [['6','1.1591','1.1146','0.9938','0.8252','0.6384'],
       ['12','0.3423','0.3343','0.3112','0.2756','0.2309'],
       ['16.87（结束）','0.1500','0.1475','0.1402','0.1287','0.1139']].map(function(r){
        return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td><td>'+r[4]+'</td><td>'+r[5]+'</td></tr>';
      }).join('')+
      '</tbody></table></div>'+
      '<p class="side-note" style="margin:12px 20px 16px">判据为"各处"水分 &lt;0.15 kg/kg——中心永远是最后达标点，16.87 h 时中心恰好 0.1500。</p>'+
    '</div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>表6 · 动边界下的水分浓度（问题四，半径收缩）</h3></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr>'+
      '<th>时间/h</th><th>0 cm</th><th>0.5 cm</th><th>1 cm</th><th>药材表面</th><th>半径/cm</th></tr></thead><tbody>'+
      [['6','2.3091','2.0676','1.3316','0.6204','1.374'],
       ['12','0.8918','0.7591','0.4471','0.2757','1.248'],
       ['18','0.2732','0.2406','0.1589','0.1176','1.214'],
       ['21.49（结束）','0.1500','0.1356','0.0991','0.0810','1.207']].map(function(r){
        return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td><td>'+r[4]+'</td><td>'+r[5]+'</td></tr>';
      }).join('')+
      '</tbody></table></div>'+
      '<p class="side-note" style="margin:12px 20px 16px">半径收缩后 1.5 cm 位置早已超出药材（6 h 时 R 已降至 1.374 cm），故表中不再有 1.5 cm 列；"药材表面"列随半径内移，其水分始终全场最低。</p>'+
    '</div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>主要结论</h3></div><div class="card-b">'+
      '<ul class="doc" style="padding:0;margin:0">'+
        '<li>预热平衡阶段"热快湿慢"：1800 s 内温度全场上升 5~8.6°C，而水分只在表面约 0.1 cm 薄层内下降（2.55→1.31），内部几乎不变——热扩散率比水分扩散系数高约 4 个数量级；</li>'+
        '<li>固定半径下烘干时长 16.87 h（问题三）；问题四虽然半径收缩 40%（2→1.207 cm）缩短了扩散路径，但附录4 扩散系数比附录3 低约 5 倍，烘干时长反而延长至 21.49 h——<b>物性差异主导、几何收缩次要</b>；</li>'+
        '<li>Landau（ξ=r/R(t)）坐标把变域 PDE 映射到固定域，收缩产生的对流项指向中心，对水分向外迁移起抑制作用；若忽略收缩（固定 R=2 cm + 附录4 物性）时长约 27 h，动边界净加快约 5.5 h；</li>'+
        '<li>工艺启示：D 对温度指数敏感（e^(−3850/T)），恒温段适度提温比单纯延长时间更有效；h 仅影响预热段，后期瓶颈在内部扩散。</li>'+
      '</ul>'+
    '</div></div>'+
    '<div class="dl-row">'+
      '<button class="dl p" onclick="toast(\'论文下载为演示功能\')">'+ICON.paper+'下载论文初稿（.docx）</button>'+
      '<button class="dl" onclick="toast(\'PDF 下载为演示功能\')">论文 PDF</button>'+
      '<button class="dl" onclick="toast(\'代码包下载为演示功能\')">完整代码包（.zip）</button>'+
      '<button class="dl" onclick="setDetailTab(\'code\')">查看可运行代码</button>'+
      '<button class="dl" onclick="setDetailTab(\'anim\')">查看可视化演示</button>'+
    '</div>';
  if(state.detailId==='p5') return '<div class="kpis">'+
      '<div class="kpi"><div class="k">问题一 · 头部消费占比</div><div class="v">41.3%</div><div class="d">头部 20 词 · 尾部 20 词占 6.8%</div></div>'+
      '<div class="kpi"><div class="k">问题二 · 分类稳健性</div><div class="v">±8%</div><div class="d">阈值 ±10% 扰动下成员变动</div></div>'+
      '<div class="kpi"><div class="k">问题三 · 再分配提升</div><div class="v">↑</div><div class="d">总期望转化 · 问题词预算下降</div></div>'+
    '<div class="kpi"><div class="k">问题四 · CVaR 区间</div><div class="v">90/95</div><div class="d">六项指标期望范围 · 区间交付</div></div>'+
  '</div>'+
    '<h3 style="font-size:15px;margin:6px 0 12px">成果 · 论文初稿与支撑材料</h3>'+
    '<div id="dlv-wrap">'+dlvHTML(dlvTab,'setDlvTab(')+'</div>'+
    '<div style="height:16px"></div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>四问方法对照（基于 2025 全年投放日志）</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'导出为演示功能\')">导出 Excel</button></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr>'+
      '<th>问题</th><th>决策粒度</th><th>核心方法</th><th>关键参数</th><th>结果</th></tr></thead><tbody>'+
      [['问题一','策略级','效益率排序 + 时间/星期均值','—','头部 20 词消费占 41.3%'],
       ['问题二','关键词级','双阈值四象限分类','消费/转化分位数','黄金/重点/潜力/问题四类'],
       ['问题三','推广单元级','饱和响应 + KKT 再分配','τ_u, λ','总期望转化提升'],
       ['问题四','不确定单元级','CVaR 约束 + 区间交付','置信 90%/95%','六项指标期望区间']].map(function(r){
         return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td><td>'+r[4]+'</td></tr>';
       }).join('')+
      '</tbody></table></div></div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>关键词四象限分类示例（问题二）</h3></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr>'+
      '<th>类别</th><th>消费</th><th>转化</th><th>处置</th></tr></thead><tbody>'+
      [['黄金词','高','高','维持预算'],
       ['重点词','低','高','加预算承接'],
       ['潜力词','低','低','择机承接'],
       ['问题词','高','低','释放预算']].map(function(r){
         return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td></tr>';
       }).join('')+
      '</tbody></table></div>'+
      '<p class="side-note" style="margin:12px 20px 16px">阈值由历史分位数标定；±10% 扰动下四类成员变动不超过 8%，分类稳健。</p>'+
    '</div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>主要结论</h3></div><div class="card-b">'+
      '<ul class="doc" style="padding:0;margin:0">'+
        '<li>问题一的本质是诊断：头部集中度偏低、尾部仍在浪费，说明原策略未按效益率配置预算；</li>'+
        '<li>问题二的四象限分类把"治理"从经验升级为阈值可解释规则，且 ±10% 扰动下稳健；</li>'+
        '<li>问题三的 KKT 条件把"预算再分配"从启发式升级为可解释的最优性：各单元边际效益相等；</li>'+
        '<li>问题四的 CVaR 区间把不确定性定位在效益端：95% 置信下效益率下界明显下移，尾部风险来自效益而非消费。</li>'+
      '</ul>'+
    '</div></div>'+
    '<div class="dl-row">'+
      '<button class="dl p" onclick="toast(\'论文下载为演示功能\')">'+ICON.paper+'下载论文初稿（.docx）</button>'+
      '<button class="dl" onclick="toast(\'PDF 下载为演示功能\')">论文 PDF</button>'+
      '<button class="dl" onclick="toast(\'代码包下载为演示功能\')">完整代码包（.zip）</button>'+
      '<button class="dl" onclick="setDetailTab(\'code\')">查看可运行代码</button>'+
      '<button class="dl" onclick="setDetailTab(\'anim\')">查看可视化演示</button>'+
    '</div>';
  return '<div class="kpis">'+
      '<div class="kpi"><div class="k">问题一 · 全天购电费</div><div class="v">35126.95</div><div class="d">元 · 确定信息下的基准解</div></div>'+
      '<div class="kpi"><div class="k">问题二 · 全年购电费</div><div class="v">1369.18</div><div class="d">万元 · 紧急购电占 6.0%</div></div>'+
      '<div class="kpi"><div class="k">问题三 · 全年总费用</div><div class="v">1535.07</div><div class="d">万元 · 紧急购电降至 1.31%</div></div>'+
    '<div class="kpi"><div class="k">问题四 · 波动电价</div><div class="v">1471.70</div><div class="d">万元 · 重算问题二；重算三为 1787.47</div></div>'+
  '</div>'+
    '<h3 style="font-size:15px;margin:6px 0 12px">成果 · 论文初稿与支撑材料</h3>'+
    '<div id="dlv-wrap">'+dlvHTML(dlvTab,'setDlvTab(')+'</div>'+
    '<div style="height:16px"></div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>四问结果对照（报告区间 2025-02-01 至 12-31，共 334 天）</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'导出为演示功能\')">导出 Excel</button></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr>'+
      '<th>问题</th><th>信息条件</th><th>核心方法</th><th>最优参数</th><th>结果</th></tr></thead><tbody>'+
      [['问题一','电价 / 负载 / 光伏全部已知','单日 MILP + 充放互斥 0-1 变量','—','购电量 59482.70 kWh<br>购电费 35126.95 元'],
       ['问题二','负载与光伏当天未知','随机森林 + 3 日滑动 + 滚动 LP','α = 0.02','总购电费 1369.18 万元<br>紧急购电占比 6.0%'],
       ['问题三','四次光伏预报，可滚动修正','多阶段滚动 LP + 调整触发阈值','α = 0.01, θ = 20 元','总费用 1535.07 万元<br>紧急购电占比 1.31%'],
       ['问题四-2','外网电价实时波动','分层电价预测 + 滚动 LP','沿用 α 网格搜索','总购电费 1471.70 万元'],
       ['问题四-3','波动电价 + 四次预报','不确定电价下的滚动修正','α = 0.02','总费用 1787.47 万元']].map(function(r){
         return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td><td>'+r[4]+'</td></tr>';
       }).join('')+
      '</tbody></table></div></div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>储能设备全天运行状态（问题一）</h3></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr>'+
      '<th>时段</th><th>充电量 (kWh)</th><th>放电量 (kWh)</th><th>时段末储电量 (kWh)</th></tr></thead><tbody>'+
      [['0:00—4:00','4500.00','0.00','10050.00'],
       ['4:00—8:00','833.33','6365.84','3726.84'],
       ['8:00—12:00','4787.96','1703.00','6143.78'],
       ['12:00—16:00','5286.04','91.10','10800.00'],
       ['16:00—20:00','0.00','5780.13','4377.63'],
       ['20:00—24:00','5333.33','2859.87','6000.00']].map(function(r){
         return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td></tr>';
       }).join('')+
      '</tbody></table></div>'+
      '<p class="side-note" style="margin:12px 20px 16px">总充电量 20740.67 kWh、总放电量 16799.94 kWh；表中为按 4 小时归并便于阅读，程序输出为 result1.xlsx 的 144 行逐时段明细。</p>'+
    '</div>'+
    '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>主要结论</h3></div><div class="card-b">'+
      '<ul class="doc" style="padding:0;margin:0">'+
        '<li>问题一的本质是储能套利：低谷把电存起来、晚高峰顶掉高价购电，全天费用比"随用随买"显著下降，且结果可通过储能递推是否闭合来自检；</li>'+
        '<li>问题二里预测偏差的代价远大于电价差本身 —— 把预测值按安全裕量 α 上抬一点，比把线性规划解得更精细更省钱，α 的最优值由网格搜索给出；</li>'+
        '<li>问题三引入 0:00 之外的预报时刻是有必要的：每加入一次预报，总费用都会下降，但边际收益递减;</li>'+
        '<li>波动电价下结论会反转：一味"多买保安全"的 α 策略在 1.5 倍结算规则下反而更贵，说明安全裕量的最优取值依赖于结算规则本身。</li>'+
      '</ul>'+
    '</div></div>'+
    '<div class="dl-row">'+
      '<button class="dl p" onclick="toast(\'论文下载为演示功能\')">'+ICON.paper+'下载论文初稿（.docx）</button>'+
      '<button class="dl" onclick="toast(\'PDF 下载为演示功能\')">论文 PDF</button>'+
      '<button class="dl" onclick="toast(\'代码包下载为演示功能\')">完整代码包（.zip）</button>'+
      '<button class="dl" onclick="setDetailTab(\'code\')">查看可运行代码</button>'+
      '<button class="dl" onclick="setDetailTab(\'anim\')">查看可视化演示</button>'+
    '</div>';
}
export function openMicrogridReplay(replayHtml){
  var box=$('microgrid-replay');
  var frame=$('microgrid-replay-frame') as HTMLIFrameElement | null;
  if(!box||!frame) return;
  if(replayHtml && replayHtml.trim()){
    /* 案例自带复现 HTML：用 srcdoc 直接渲染，不再回落 p3 静态文件 */
    frame.removeAttribute('src');
    frame.srcdoc = replayHtml;
  } else {
    /* 无自带复现：回落到 p3 内置微网演示（兜底） */
    if(frame.getAttribute('srcdoc')) frame.removeAttribute('srcdoc');
    if(frame.src==='about:blank') frame.src='./cases/p3/microgrid-replay.html';
  }
  box.classList.add('on');
  box.setAttribute('aria-hidden','false');
  document.body.style.overflow='hidden';
}
export function closeMicrogridReplay(){
  var box=$('microgrid-replay');
  if(!box) return;
  box.classList.remove('on');
  box.setAttribute('aria-hidden','true');
  document.body.style.overflow='';
}
export function openWorkFromDetail(){
  var p=PROBLEMS.filter(function(x){return x.id===state.detailId})[0];
  var m=outsourcedCase(state.detailId);
  if(m&&m.replayHtml){
    openMicrogridReplay(m.replayHtml);
    return;
  }
  if(p&&p.id==='p3'){
    openMicrogridReplay();
    return;
  }
  startWork((p?p.tag+'：'+p.title:''),true);
}
/* ================= 工作台 ================= */
export let STEPS =['全局分析','问题一','问题二','问题三','问题四','论文'];
export let ART ={
  analysis:{c:'linear-gradient(120deg,#12A594,#17A2C4)',t:'问题分析',
    icon:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"><path d="M9 18h6M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/></svg>'},
  animation:{c:'linear-gradient(120deg,#17A2C4,#2EC4B0)',t:'可视化演示',
    icon:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v17a1 1 0 0 0 1 1h17"/><rect x="7" y="12" width="3" height="6"/><rect x="12.5" y="8" width="3" height="10"/><rect x="18" y="4.5" width="3" height="13.5"/></svg>'},
  model:{c:'linear-gradient(120deg,#F5A623,#FF7E00)',t:'模型方案',
    icon:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"><path d="M12 2l9 5v10l-9 5-9-5V7z"/><path d="M12 22V12M3 7l9 5 9-5"/></svg>'},
  code:{c:'linear-gradient(120deg,#0B2E36,#1E6FA8)',t:'可运行代码',
    icon:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 18l6-6-6-6"/><path d="M8 6l-6 6 6 6"/></svg>'},
  result:{c:'linear-gradient(120deg,#2BA471,#12A594)',t:'运行结果',
    icon:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"><path d="M3 17l5-6 4 3 5-7 4 4"/><path d="M3 21h18"/></svg>'},
  paper:{c:'linear-gradient(120deg,#0B6E68,#2EC4B0)',t:'论文初稿',
    icon:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'},
  env:{c:'linear-gradient(120deg,#0B6E68,#2BA471)',t:'运行环境',
    icon:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="14" rx="2"/><path d="M2 9h20M6 14h2M11 14h2"/></svg>'}
};
export function AR(type,title,sub,k){ return {type:type,title:title,sub:sub,k:k}; }

/* ---------- 演示按流程逐步生成 ----------
   走到哪一步，才生成哪一步的演示；未走到的步骤不出现在列表里。
   ANIM_BANK 记录"已生成"的演示，点击列表即可回看。
   ---------------------------------------- */
/* ANIM_FULL = true 表示正在看"平台已完赛案例"（5 张全部可看）；
   进入工作台后被置 false，此后只有 ANIM_BANK 里登记过的才出现在列表里 */
export let ANIM_BANK =[], ANIM_CUR='0', ANIM_FULL=true;
export let ALL_ANIMS =[
  ['0','全局分析 · 通用名词 / 数据 / 约束 / 四问关系'],
  ['1','问题一 · 确定信息下的储能套利轨迹'],
  ['2','问题二 · 全年滚动的计划 vs 实际费用累计'],
  ['3','问题三 · 四次预报下的计划修正效果'],
  ['4','问题四 · 波动电价与预测带宽叠加演示']
];
/* E 题（p5 · SEM 广告投放）演示集 —— 与 public/cases/p5/anims/emod-0~4.html 一一对应 */
export let ALL_ANIMS_E =[
  ['0','全局分析 · 规模核对 / 三附件对应四问 / 五方案十二单元 / 结构性事实'],
  ['1','问题一 · 投放策略合理性 / 效益时间规律 / 星期层面规律'],
  ['2','问题二 · 关键词四象限分类 / 阈值稳健性 / 分类结果落地'],
  ['3','问题三 · 单元级投放模型 / 选词与预算再分配 / 逐日单元明细'],
  ['4','问题四 · 不确定性量级 / 六项指标期望 / 建模路径与交付']
];
/* A 题（中药材热风烘干）演示集 —— 进 p1 时由 setAnimSet() 激活 */
export let P1_ANIMS =[
  ['0','全局分析 · 机理结构 / 两阶段工艺 / 物性 / 四问关系'],
  ['1','问题一 · 预热平衡的温度场与水分边界层演化'],
  ['2','问题二 · 全过程热湿耦合双场演化'],
  ['3','问题三 · 烘干时长判定与 Cmax 阈值搜索'],
  ['4','问题四 · 动边界收缩与 Landau 坐标变换']
];
/* B 题（无线电干扰源定位 · p2）演示集 —— 与 public/cases/p2/anims/rid-0~4.html 一一对应 */
export let P2_ANIMS =[
  ['0','全局分析 · 干扰源定位整体框架 / 数据 / 约束 / 四问关系'],
  ['1','问题一 · 干扰源定位分析'],
  ['2','问题二 · 干扰源定位分析'],
  ['3','问题三 · 干扰源定位分析'],
  ['4','问题四 · 干扰源定位分析']
];
/* p2 目前只有动画：指导书 / 代码 / 成果 / 支撑材料均无内容。
   按「有就显示、没有就留白」的原则置空，不串用其它题（p3 微网）的内置内容。 */
export let P2_BOOK:any[] = [];
export let P2_PY_CODE = '';
export let P2_RUN_OUT = '';
export let P2_SUPPORT = { code: [], image: [], file: [] };
/* p2 无专属演示截图 → animShotHTML 走 iframe 直挂真实动画文件 */
export const ANIM_SHOT_P2: Record<string, string> = {};
export function genAnim(k,title,sub){
  for(var i=0;i<ANIM_BANK.length;i++){
    if(ANIM_BANK[i].k===k){ ANIM_CUR=k; return; }
  }
  ANIM_BANK.push({k:k,title:title,sub:sub});
  ANIM_CUR=k;
  refreshAnimDrawer();          /* 新生成一张：抽屉开着就立刻把这张演示加进列表 */
}
/* 抽屉正在看演示时，跟着流程进度刷新列表 */
export function refreshAnimDrawer(){
  if(!state.drawerOpen||state.drawerType!=='animation') return;
  var b=$('drawer-body'); if(!b) return;
  b.innerHTML=artBody('animation');
  drawerAnim(ANIM_CUR);          /* 重渲染后回到当前这张演示（列表保持展开态） */
}
export function regAnims(m){
  /* ~接入改动：真实动画接管期间不登记内置演示（免得真实与内置混在一张列表里）*/
  if(animRealActive()){ animRealRegister(); return; }
  if(!m||!m.arts) return;
  for(var i=0;i<m.arts.length;i++){
    var a=m.arts[i];
    if(a.type==='animation') genAnim(a.k||'0',a.title,a.sub);
  }
}
/* ---------- 演示播放器 · 全屏遮罩层 ----------
   舞台里是静态截图占位；点击右上角全屏图标 → 弹出全屏遮罩层，
   把内联在本文件的动画 <template> 通过 Shadow DOM 挂载进来（全程无 iframe、无外部文件）；
   顶部 tab 可直接切换演示，不退出全屏。ESC 或「退出全屏」按钮关闭，回到原型页面。 */
export let IC_FS ='<svg class="ico-fs" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3H3v6"/><path d="M15 3h6v6"/><path d="M15 21h6v-6"/><path d="M9 21H3v-6"/></svg>';
/* ===== 五个可视化演示：HTML / CSS / JS 全部内联在本文件里
       （见文件底部的 <template id="anim-tpl-0..4">），
       双击 file:// 打开即可播放，不依赖外部 HTML、不需要本地服务器、也不使用 iframe ===== */
/* 静态资源已按案例分目录：public/cases/<protoId>/{anims,anim-shots}/
   —— 详见 public/cases/README.md */
export const ANIM_SHOT: Record<string, string> = {
  '0': './cases/p3/anim-shots/0.png',
  '1': './cases/p3/anim-shots/1.png',
  '2': './cases/p3/anim-shots/2.png',
  '3': './cases/p3/anim-shots/3.png',
  '4': './cases/p3/anim-shots/4.png'
};
/* E 题演示截图 —— 由 emod-0~4.html 截取后放入 public/cases/p5/anim-shots/ */
export const ANIM_SHOT_E: Record<string, string> = {
  '0':'./cases/p5/anim-shots/0.png','1':'./cases/p5/anim-shots/1.png',
  '2':'./cases/p5/anim-shots/2.png','3':'./cases/p5/anim-shots/3.png','4':'./cases/p5/anim-shots/4.png'
};
/* 当前激活演示集 —— 进题时由 setAnimSet() 按题目切换；
   列表 / 截图 / 文件前缀三元组同源切换，避免 C/E 题演示串扰 */
export let ANIM_CUR_SET = ALL_ANIMS;
export let ANIM_SHOT_CUR = ANIM_SHOT;
export let ANIM_PREFIX_CUR = 'mod-';
/* 案例静态资源目录名（public/cases/<ANIM_DIR_CUR>/），与 ANIM_PREFIX_CUR 同源切换 */
export let ANIM_DIR_CUR = 'p3';
export function setAnimSet(pid:string, keepBank?:boolean){
  if(pid==='p5'){ ANIM_CUR_SET=ALL_ANIMS_E; ANIM_SHOT_CUR=ANIM_SHOT_E; ANIM_PREFIX_CUR='emod-'; ANIM_DIR_CUR='p5'; BOOK_CUR=BOOK_E; PY_CODE_CUR=PY_CODE_E; RUN_OUT_CUR=RUN_OUT_E; SUPPORT_CUR=SUPPORT_E; }
  else if(pid==='p1'){ ANIM_CUR_SET=P1_ANIMS; ANIM_SHOT_CUR=ANIM_SHOT; ANIM_PREFIX_CUR='mod-p1-'; ANIM_DIR_CUR='p1'; BOOK_CUR=P1_BOOK; PY_CODE_CUR=P1_PY_CODE; RUN_OUT_CUR=P1_RUN_OUT; SUPPORT_CUR=P1_SUPPORT; }
  else if(pid==='p2'){ ANIM_CUR_SET=P2_ANIMS; ANIM_SHOT_CUR=ANIM_SHOT_P2; ANIM_PREFIX_CUR='rid-'; ANIM_DIR_CUR='p2'; BOOK_CUR=P2_BOOK; PY_CODE_CUR=P2_PY_CODE; RUN_OUT_CUR=P2_RUN_OUT; SUPPORT_CUR=P2_SUPPORT; }
  else { ANIM_CUR_SET=ALL_ANIMS; ANIM_SHOT_CUR=ANIM_SHOT; ANIM_PREFIX_CUR='mod-'; ANIM_DIR_CUR='p3'; BOOK_CUR=BOOK; PY_CODE_CUR=PY_CODE; RUN_OUT_CUR=RUN_OUT; SUPPORT_CUR=SUPPORT; }
  /* 切题清空工作台会话登记的演示与当前指针，避免上一题的登记项混入新题列表；
     会话恢复(workSessionResume)时保留，由恢复逻辑自行重建 ANIM_BANK */
  if(!keepBank){ ANIM_BANK=[]; ANIM_CUR='0'; }
}
export function animShotHTML(k){
  var a=animMeta(k);
  /* 无专属截图资源的案例（A 题 p1 中药材 / B 题 p2 无线电）→ 直接在舞台用 iframe 挂载真实动画文件 */
  if(state.detailId==='p1' || !ANIM_SHOT_CUR[k]){
    var _src=animRealURL(k);
    if(!_src) _src='./cases/'+ANIM_DIR_CUR+'/anims/'+ANIM_PREFIX_CUR+k+'.html';
    return '<iframe class="anim-frame" src="'+_src+'" title="'+esc(a)+' 可视化演示" loading="lazy"></iframe>';
  }
  return '<img class="anim-shot" src="'+(ANIM_SHOT_CUR[k]||'')+'" alt="'+esc(a)+' 可视化演示截图" loading="lazy">';
}
export function animMeta(k){
  for(var i=0;i<ANIM_CUR_SET.length;i++) if(ANIM_CUR_SET[i][0]===k) return ANIM_CUR_SET[i][1].split(' · ')[0];
  return '可视化演示';
}
export function fsBtnHTML(k){
  return '<button type="button" class="stage-fs" aria-label="全屏" title="全屏" onclick="openAnimFs(\''+k+'\')">'+
    IC_FS+'<i class="tip">全屏</i></button>';
}
export let AFS_K =null;          /* 全屏层当前演示 */
export let ANIM_HOST =null;      /* 当前挂载的演示宿主 */
export let ANIM_CLEANUP =[];     /* 卸载时要清理的定时器 / 监听 */
export function openAnimFs(k){
  if(!animDone(k)){ pendAnim(k); return; }
  var box=$('afs'); if(!box) return;
  AFS_K=k;
  renderAfsTabs(k);
  box.classList.add('on');
  box.setAttribute('aria-hidden','false');
  document.body.style.overflow='hidden';
  mountAnim(k);            /* 先显示再挂载：动画初始化时能量到真实宽高 */
}
export function switchAfsTab(k){
  if(!animDone(k)){ pendAnim(k); return; }   /* 未生成的演示：不切换内容，提示先走流程 */
  AFS_K=k;
  renderAfsTabs(k);
  mountAnim(k);            /* 先卸载上一张，再把这张的内联内容挂进来 */
}
export function closeAnimFs(){
  AFS_K=null;
  unmountAnim();           /* 拆掉内联演示，清空它的定时器与全局监听 */
  var box=$('afs'); if(!box) return;
  box.classList.remove('on');
  box.setAttribute('aria-hidden','true');
  document.body.style.overflow='';
}
/* ---------- 内联挂载：把 template 里的动画挂到全屏层（样式互不污染） ---------- */
export function unmountAnim(){
  for(var i=0;i<ANIM_CLEANUP.length;i++){ try{ ANIM_CLEANUP[i](); }catch(e){} }
  ANIM_CLEANUP=[];
  var holder=$('afs-stage');
  if(holder) holder.innerHTML='';
  ANIM_HOST=null;
}
export /* ~待 Vue 化：原实现依赖内联 <template>，演示模块已外置为独立页面，
   此处保留原码留档，实际挂载见文件末尾的 mountAnim（iframe 版）。 */
function mountAnimLegacy(k){
  unmountAnim();
  var holder=$('afs-stage'); if(!holder) return false;
  var tpl=$('anim-tpl-'+k); if(!tpl||!tpl.content) return false;
  var host=document.createElement('div');
  host.className='anim-host'; host.setAttribute('data-anim',k);
  holder.appendChild(host);
  ANIM_HOST=host;
  var root=null;
  try{ root=host.attachShadow({mode:'open'}); }catch(e){ root=host; }   /* 老浏览器降级：直接挂宿主里 */
  try{ root.appendChild(tpl.content.cloneNode(true)); }
  catch(e){ try{ root.innerHTML=tpl.innerHTML; }catch(e2){} }
  var s=(root.querySelector?root.querySelector('script[data-anim-main]'):null);
  if(s&&s.textContent){
    try{ runAnimScript(s.textContent,root,host); }catch(err){}   /* 脚本出错也不影响静态结构 */
  }
  return true;
}
/* 在一个隔离作用域里执行动画自带的脚本：
   document / window 都是代理：查询只在演示自己的 DOM 里找，塞给 window 的东西不污染原型全局；
   记录它注册的定时器与 window / document 监听，卸载时一次性清掉。 */
export function runAnimScript(code,root,host){
  var realWin=window, realDoc=document;
  var timers=[], rafs=[], listens=[];
  function mkAdd(p){
    return function(){ listens.push([p,arguments[0],arguments[1],arguments[2]]); return p.addEventListener.apply(p,arguments); };
  }
  ANIM_CLEANUP.push(function(){
    try{
      for(var i=0;i<timers.length;i++){ clearTimeout(timers[i]); clearInterval(timers[i]); }
      for(var j=0;j<rafs.length;j++) cancelAnimationFrame(rafs[j]);
      for(var m=0;m<listens.length;m++){
        try{ listens[m][0].removeEventListener(listens[m][1],listens[m][2],listens[m][3]); }catch(e){}
      }
    }catch(e){}
    timers=[]; rafs=[]; listens=[];
  });
  var docProxy=new Proxy(realDoc,{
    get:function(t,p){
      if(p==='body'||p==='documentElement') return host;
      if(p==='addEventListener') return mkAdd(t);
      if(p==='getElementById') return function(id){
        try{ var r=root.querySelector('[id="'+String(id).replace(/["\\]/g,'')+'"]'); if(r) return r; }catch(e){}
        return t.getElementById(id);
      };
      if(p==='querySelector') return function(sel){
        try{ var r=root.querySelector(sel); if(r) return r; }catch(e){}
        return t.querySelector(sel);
      };
      if(p==='querySelectorAll') return function(sel){
        try{ var r=root.querySelectorAll(sel); if(r&&r.length) return r; }catch(e){}
        return t.querySelectorAll(sel);
      };
      if(p==='getElementsByClassName'||p==='getElementsByTagName'||p==='getElementsByName'){
        return function(){
          try{ var r=root[p].apply(root,arguments); if(r&&r.length) return r; }catch(e){}
          return t[p].apply(t,arguments);
        };
      }
      var v=t[p];
      if(typeof v==='function') return v.bind(t);
      return v;
    }
  });
  var sandbox={};
  var winProxy=new Proxy(realWin,{
    get:function(t,p){
      var v=(p in sandbox)?sandbox[p]:t[p];
      if(typeof v==='function'){
        if(p==='addEventListener') return mkAdd(t);
        if(p==='removeEventListener') return function(){ return t.removeEventListener.apply(t,arguments); };
        if(p==='setTimeout') return function(){ var id=t.setTimeout.apply(t,arguments); timers.push(id); return id; };
        if(p==='setInterval') return function(){ var id=t.setInterval.apply(t,arguments); timers.push(id); return id; };
        if(p==='requestAnimationFrame') return function(){ var id=t.requestAnimationFrame.apply(t,arguments); rafs.push(id); return id; };
        if(p==='clearTimeout'||p==='clearInterval'||p==='cancelAnimationFrame') return function(){ return t[p].apply(t,arguments); };
        return v.bind(t);
      }
      return v;
    },
    set:function(t,p,val){ sandbox[p]=val; return true; }
  });
  var runner=new Function('document','window','AHOST','setTimeout','setInterval','requestAnimationFrame','clearTimeout','clearInterval','cancelAnimationFrame',
    '(function(document,window,AHOST,setTimeout,setInterval,requestAnimationFrame,clearTimeout,clearInterval,cancelAnimationFrame){'+
    code+
'\n})(document,window,AHOST,setTimeout,setInterval,requestAnimationFrame,clearTimeout,clearInterval,cancelAnimationFrame);');
  function mkST(){ return function(){ var id=realWin.setTimeout.apply(realWin,arguments); timers.push(id); return id; }; }
  function mkSI(){ return function(){ var id=realWin.setInterval.apply(realWin,arguments); timers.push(id); return id; }; }
  function mkRAF(){ return function(){ var id=realWin.requestAnimationFrame.apply(realWin,arguments); rafs.push(id); return id; }; }
  function mkC(name){ return function(){ return realWin[name].apply(realWin,arguments); }; }
  runner(docProxy,winProxy,host,mkST(),mkSI(),mkRAF(),mkC('clearTimeout'),mkC('clearInterval'),mkC('cancelAnimationFrame'));
}
/* 全屏层顶部 tab：只放已生成的演示；未走到的步骤置灰「待生成」，点了不切换 */
export function renderAfsTabs(cur){
  var box=$('afs-tabs'); if(!box) return;
  box.innerHTML=ANIM_CUR_SET.map(function(a){
    if(!animDone(a[0])){
      return '<button type="button" class="afs-tab lock" title="还没走到这一步，流程生成后即可查看" onclick="pendAnim(\''+a[0]+'\')">'+
        esc(a[1].split(' · ')[0])+'<span class="lk"> · 待生成</span></button>';
    }
    return '<button type="button" class="afs-tab'+(a[0]===cur?' on':'')+'" onclick="switchAfsTab(\''+a[0]+'\')">'+
      esc(a[1].split(' · ')[0])+'</button>';
  }).join('');
}
document.addEventListener('keydown',function(e){
  if((e.key==='Escape'||e.key==='Esc')&&AFS_K!==null) closeAnimFs();
});
/* 演示列表：未走到的步骤**不出现在列表里**。
   详情页（ANIM_FULL=true，看平台已完赛案例）→ 5 张全部列出；
   工作台会话 → 只列出已经登记进 ANIM_BANK 的演示。 */
export function animDone(k){
  if(ANIM_FULL) return true;
  for(var i=0;i<ANIM_BANK.length;i++) if(ANIM_BANK[i].k===k) return true;
  return false;
}
export function pendAnim(k){
  var a=null;
  for(var i=0;i<ANIM_CUR_SET.length;i++) if(ANIM_CUR_SET[i][0]===k) a=ANIM_CUR_SET[i];
  toast('「'+(a?a[1].split(' · ')[0]:'这一步')+'」还没走到，进入这一步后会自动生成对应的可视化演示');
}
export function animList(){
  var map={}, out=[];
  for(var i=0;i<ANIM_BANK.length;i++) map[ANIM_BANK[i].k]=ANIM_BANK[i];
  for(var j=0;j<ANIM_CUR_SET.length;j++){
    var a=ANIM_CUR_SET[j], g=map[a[0]];
    if(ANIM_FULL) out.push({k:a[0],title:a[1],sub:g?g.sub:'全流程已跑完 · 点击回看',done:true});
    else if(g)    out.push({k:g.k,title:g.title,sub:g.sub,done:true});
  }
  return out;
}

/* ==================================================================
   剧本数据：两层分析
     第一层 · 全局分析（演示一）
     第二层 · 四个问题各自分析（每问一张演示，共演示二～五）
   每一问的标准闭环：
     分析 → 候选模型（用户点选）→ 生成代码 → 在本页面运行代码
            → 展示并解读结果 → 用户评价（满意进入下一问 / 不满意调整 / 换模型）
   四问完成后：选论文结构 → 选图表数量 → 勾选论文图片 → 生成论文初稿
   用户可随时跳过当前步，也可点顶部进度条回退重做
   ================================================================== */
export let INTRO_NODE ={step:0} as any;
export let PAPER_CHIPS =[] as any[];
export let QUIZ =[
/* ~接入改动（2026-10-08）：内置演示剧本已删除，这里只保留流程骨架字段。
   分析 / 候选模型 / 结果 / 排查建议一律由智能体实时生成。 */
{n:1,cn:'一',step:1,key:'q1',file:'result1.xlsx',codeName:'main.py'},
{n:2,cn:'二',step:2,key:'q2',file:'result2.xlsx',codeName:'model_q2.py'},
{n:3,cn:'三',step:3,key:'q3',file:'result3.xlsx',codeName:'model_q3.py'},
{n:4,cn:'四',step:4,key:'q4',file:'result4-2.xlsx、result4-3.xlsx',codeName:'model_q4.py'}
] as any[];
export let IC_LAB ='<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5"/><path d="M21 12H9"/></svg>';
export let IC_UP ='<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M17 8l-5-5-5 5"/><path d="M12 3v12"/></svg>';
export let IC_OK ='<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>';
export let IC_WARN ='<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>';
export let IC_SWAP ='<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 3h5v5"/><path d="M4 20L21 3"/><path d="M21 16v5h-5"/><path d="M15 15l6 6"/><path d="M4 4l5 5"/></svg>';
export let IC_FILE ='<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>';
export let IC_EDIT ='<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L8 18l-4 1 1-4z"/></svg>';

/* ---------------- 引擎：一次一条，由用户驱动 ----------------
   ① 两层分析：全局分析（演示一）→ 各问题分析（每问一张演示）
   ② 代码生成 → 当前页面运行 → 显示输出或报错 → 结果解读 → 用户判定走向
   ③ 用户可跳过当前步，也可点顶部进度条回退重做
   ④ 每一步结束后，AI 都会明确说出"下一步做什么"
   ------------------------------------------------------------ */
export let qi =0, phase='intro', pickRound=1, pendingPick=null, pickedMap={}, SEQ=0, CUR_ACTS=null, curStep=0;
export let FLOW_GATE =null, FLOW_HISTORY=[], FLOW_PREFS={}, FLOW_PAUSED=false, FLOW_PAUSE_PHASE=null, SELECTED_PICK=null;
export let PHASE_LABEL ={
  intro:'等待你的指令',
  interaction:'等待：点一个选项，或跳过',
  needUnderstanding:'等待：确认题意理解',
  understandingSupplement:'等待：补充题目理解',
  needCompare:'等待：决定是否对比模型',
  needParameter:'等待：选择参数设定方式',
  needResultAngle:'等待：选择结果解读角度',
  needSatisfaction:'等待：评价当前结果',
  needPaperStyle:'等待：选择论文风格',
  needFigureSelection:'等待：勾选论文图片',
  needAnalysis:'等待：展开这一步的分析',
  needPicks:'等待：索要候选模型',
  picking:'等待：你在候选卡片上点「选它」',
  lab:'等待：在当前页面运行代码',
  adjust:'等待：你描述遇到的问题',
  needPaper:'等待：生成论文初稿',
  done:'全部完成'
};
export function setPhase(p){
  phase=p;
  var el=$('w-stage');
  if(!el) return;
  var done=(p==='done');
  el.className='stag'+(done?' done':'');
  el.innerHTML='<i></i>'+esc(PHASE_LABEL[p]||'等待你的指令');
}
export function mkey(){ SEQ++; return 'm'+SEQ; }
export function scrollBottom(){ var st=$('stream'); if(st) st.scrollTop=st.scrollHeight; }
export function renderSteps(cur){
  curStep=cur;
  var el=$('w-steps'); if(!el) return;
  el.innerHTML=STEPS.map(function(s,i){
    var cls = i<cur?'done':(i===cur?'now':'');
    var can = i<=cur;
    return (i?'<span class="step-line"></span>':'')+
      '><span class="n">'+(i<cur?'✓':(i+1))+'</span>'+s+'</span>';
  }).join('');
}
/* 回退：重新走某一步（只允许回到已经走过的步） */
export function artHTML(a,key){
  var m=ART[a.type];
  return '<button class="acard" id="art-'+key+'" onclick="openArt(\''+a.type+'\',\''+key+'\')">'+
    (a.autoBadge?'<span class="auto">自动弹出</span>':'')+
    '<span class="ai" style="background:'+m.c+'">'+m.icon+'</span>'+
    '<span class="at"><b>'+esc(a.title)+'</b><span>'+esc(a.sub)+'</span></span>'+
    '<span class="arrow">查看<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg></span>'+
  '</button>';
}
export function actsHTML(node,key){
  if(!node.acts||!node.acts.length) return '';
  return '<div class="acts" id="acts-'+key+'">'+node.acts.map(function(b,i){
    return '<button class="abtn '+(b.cls||'gh')+'" id="abtn-'+key+'-'+i+'" onclick="'+b.f+'">'+b.ico+esc(b.t)+'</button>';
  }).join('')+'</div>';
}
export function fileChipsHTML(names){
  return '<div class="frecv">'+names.map(function(n){
    return '<span class="fchip">'+IC_FILE+'<span><b>'+esc(n)+'</b><span> · 已接收</span></span></span>';
  }).join('')+'</div>';
}
export function msgHTML(m,key){
  var isMe=(m.who==='me');
  return '<div class="msg '+(isMe?'me':'ai')+'">'+
    '<div class="bubble"><div class="txt">'+m.html+'</div>'+
    actsHTML(m,key)+
    (m.arts?'<div class="arts">'+m.arts.map(function(a,i){
        if(a.type===m.auto) a.autoBadge=true;
        return artHTML(a,key+'-'+i);
      }).join('')+'</div>':'')+
    '</div></div>';
}
export function pushMsg(m,key){
  key=key||mkey();
  regAnims(m);                 /* 走到这一步，才把这一步的演示登记进演示列表 */
  var div=document.createElement('div');
  div.innerHTML=msgHTML(m,key);
  workHost().appendChild(div.firstChild);
  scrollBottom();
  return key;
}
export function openArtOfType(m,key,type){
  if(!m||!m.arts) return;
  for(var i=0;i<m.arts.length;i++){
    if(m.arts[i].type===type){ openArt(type,key+'-'+i); return; }
  }
}
export function autoOpenArt(m,key){
  if(!m||!m.auto||!m.arts) return;
  for(var i=0;i<m.arts.length;i++){
    if(m.arts[i].type===m.auto){
      (function(idx){ setTimeout(function(){ openArt(m.auto,key+'-'+idx); },700); })(i);
      return;
    }
  }
}
export function thinking(cb){
  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><span class="thinking"><i></i><i></i><i></i>AI 正在思考</span></div>';
  workHost().appendChild(el);
  scrollBottom();
  setTimeout(function(){ el.remove(); cb(); scrollBottom(); },820);
}
/** 开题气泡的正文：题面 + 这次交了什么文件（~接入改动 2026-10-12） */
function submitBriefHTML(title){
  var h='<span class="ub-title">'+esc(title||'（未命名题目）')+'</span>';
  var H=getAnalysisHooks();
  var list=(H&&H.heldList)?(H.heldList('home')||[]):[];
  var prob=[],data=[];
  for(var i=0;i<list.length;i++){
    if(list[i].kind==='problem') prob.push(list[i].name); else data.push(list[i].name);
  }
  if(prob.length||data.length){
    h+='<div class="ub-files">'+submitChipsHTML('题目',prob)+submitChipsHTML('附件',data)+'</div>';
  }else{
    h+='<div class="ub-none">未提交文件</div>';
  }
  return h;
}
function submitChipsHTML(kind,names){
  var h='';
  for(var i=0;i<names.length;i++){
    h+='<span class="fchip">'+IC_FILE+'<span><b>'+esc(names[i])+'</b><span> · '+kind+'</span></span></span>';
  }
  return h;
}
export function startWork(title,fromDetail){
  state.workTitle=title||'新题目';
  workSessionBegin(!!fromDetail);   /* ~接入改动：登记一条工作台任务（任务仓） */
  railGateReset();   /* ~接入改动（2026-10-12）：开新题时右栏不能留着上一条任务的闸门 */
  $('w-title').textContent=state.workTitle.length>46?state.workTitle.slice(0,46)+'…':state.workTitle;
  $('w-tag').textContent=fromDetail?'2026 真题复现':'新题目';
  workHost().innerHTML='';
  closeDrawer(true);
  qi=0; phase='intro'; pickRound=1; pendingPick=null; pickedMap={}; SEQ=0; CUR_ACTS=null;
  FLOW_GATE=null; FLOW_HISTORY=[]; FLOW_PREFS={}; FLOW_PAUSED=false; FLOW_PAUSE_PHASE=null; SELECTED_PICK=null;
  ANIM_BANK=[]; ANIM_CUR='0'; ANIM_FULL=false;  /* 重新开始：演示列表清空，按流程逐步追加 */
  renderSteps(0);
  setPhase('intro');
  go('work');
  /* ~接入改动：fromDetail 为假时 detailId 是上一道题的残留，不能拿它判「是不是演示题」*/
  var p=fromDetail?PROBLEMS.filter(function(x){return x.id===state.detailId})[0]:null;
  var isDemo=!!(p&&p.demo);
  var up=isDemo
    ? '（已上传题目及附件）<br><b>2026 国赛 C 题</b>：微网与外部电网的电力调控策略。<br>附件1.xlsx（144 个时段的电价 / 小区负载 / 光伏预测）、附件2.xlsx（2025 全年逐日实际负载与光伏）、附件3.xlsx（0:00 / 6:00 / 12:00 / 18:00 四时刻预报）、附件4.xlsx（全年分时电价）。'
    : '（题目已上传）<br>'+esc(state.workTitle);
  /* ~接入改动：这条用户消息记的是「用户干了什么」+「交了什么文件」，不是一条系统回执 */
  if(!isDemo) up=submitBriefHTML(state.workTitle);
  up=(fromDetail?'在平台复现一遍':'开始演易')+'<br>'+up;
  pushMsg({who:'me',html:up},mkey());
  /* ~接入改动（2026-10-08）：开场白只认智能体的全局分析产出，内置剧本已删 */
  /* ~接入改动：换了一道题，先摘掉上一题已上传的附件。
     只摘「已上传」，不动还没上传的 —— 首页是「先选文件、再点开始演易」，
     这个动作发生在开题那一刻，把待上传的清掉就白选了 */
  attachBeginProblem();
  /* ~接入改动：新起一道题，把三条真实通道的上一题结果清掉（动画/代码是本题专属）*/
  realChannelsReset();
  /* ~接入改动：有真实通道的题目先读题再走流程；其余题目行为不变。
     动画在后台生成（不阻塞对话），进度在右下角小浮窗里 */
  /* ~接入改动（2026-10-12）：进工作台只发**一条** run（智能体全权驱动）。
     过去这里 cb 里还要接着走（全局分析 / 题意确认 / 逐问分析）那套脚本，
     现在没有了：分析、四问、论文全部由智能体自己推进，前端只画事件流与产物卡。 */
  /* ~接入改动（2026-10-13）：动画通道与解答**一起**叫起来 —— 原来挂在
     analysisBoot 的 cb 里，那是「分析跑完才接动画」，与用户定版的「解答开跑
     就在后台跑动画」相反。analysisFromDetail 要先记下：animationBoot 取题面
     用的是这个模块变量，analysisBoot 自己也会再设一遍（同一个值，无害）。 */
  analysisFromDetail=!!fromDetail;
  animationBoot();
  analysisBoot(state.workTitle,!!fromDetail,function(){});
}
/* 第一层：全局分析（演示一） */
export function sendInWork(){
  var ta=$('work-input');
  var v=ta.value.trim();
  if(!v){ toast('和 AI 说一句话，它才会继续下一步'); return; }
  ta.value=''; ta.style.height='auto';
  pushMsg({who:'me',html:esc(v).replace(/\n/g,'<br>')},mkey());
  /* ~接入改动（2026-10-12）：不再插一条 820ms 的假思考气泡 —— 后端事件流
     自己会画思考折叠条与转圈，多插一条只会显得卡顿 */
  userTurn(v);
}
/* ~接入改动（2026-10-12）：对话区不再有写死的分支树。
   你说一句，就原样交给智能体：它自己决定是回答、是接着做，还是反过来问你。
   它问你时走平台的真闸门（ui_form_advanced / ui_gate）—— 前端画的写死选择卡已删。 */
export function userTurn(v){
  intentAsk(v);
}
export function openArtifactView(title,sub,html){
  state.drawerOpen=true; state.drawerType='artifact';
  railArtView(true);   /* ~接入改动：右栏切到产物预览态 */
  var d=$('drawer'); if(!d) return;
  d.classList.add('on'); d.classList.remove('wide');
  var t=$('d-title2'); if(t) t.innerHTML=esc(title||'产物')+'<span class="sub">'+esc(sub||'')+'</span>';
  var b=$('drawer-body'); if(b) b.innerHTML=html||'';
  var c=$('btn-drawer-close'); if(c) c.style.display='';
}
export function openArt(type,key){
  var m=ART[type];
  openDrawer(type,m.t,'点击左侧卡片查看 / 可手动收起');
  var cards=document.querySelectorAll('.acard');
  for(var i=0;i<cards.length;i++) cards[i].classList.remove('open');
  var el=$('art-'+key);
  if(el) el.classList.add('open');
}
export function openDrawer(type,title,sub){
  railArtView(true);   /* ~接入改动（2026-10-12）：右栏切到产物预览态 */
  state.drawerOpen=true; state.drawerType=type;
  var d=$('drawer');
  d.classList.add('on');
  if(type==='code'||type==='paper') d.classList.add('wide'); else d.classList.remove('wide');
  $('d-title2').innerHTML=esc(title)+'<span class="sub">'+esc(sub||'')+'</span>';
  $('drawer-body').innerHTML=artBody(type);
  if(type==='code') wireRealCodeActions();   /* ~接入改动：真实代码的复制/下载在这里接上 */
  $('btn-drawer-close').style.display='';
  if(type==='animation'){ drawerAnim(ANIM_CUR); }
}
export function closeDrawer(silent){
  state.drawerOpen=false;
  $('drawer').classList.remove('on','wide');
  railArtView(false);   /* ~接入改动：右栏回到产物清单 */
  $('btn-drawer-close').style.display='none';
  var cards=document.querySelectorAll('.acard');
  for(var i=0;i<cards.length;i++) cards[i].classList.remove('open');
}
export function drawerAnim(k){
  if(!animDone(k)){ pendAnim(k); return; }
  ANIM_CUR=k;
  /* ~接入改动：真实档位用 blob URL 挂 iframe（与全屏层同一隔离口径）*/
  var stageShot=animRealURL(k)?animRealFrameHTML(k):animShotHTML(k);
  var st=$('d-stage'); if(st) st.innerHTML=fsBtnHTML(k)+stageShot;   /* 截图占位 + 全屏按钮 */
  wireAnimTabs();   /* ~接入改动：真实列表是渲染后才有的节点，事件在这里补绑 */
  ANIM_CUR_SET.map(function(a){return a[0]}).forEach(function(i){
    var el=$('dq-'+i); if(el) el.classList.toggle('open',i===k);
  });
}

export function artBody(type){
  /* ~接入改动：有智能体产出的分析时优先展示（没有则走下面的内置文档） */
  var live=analysisLiveBody(type);
  if(live) return live;
  if(type==='analysis'){
    return '<div class="card"><div class="card-h">'+ART.analysis.icon.replace('#fff','#12A594')+'<h3>问题分析</h3></div><div class="card-b">'+
      '<div class="doc" style="padding:0">'+
      '<h3>1. 问题目标</h3><p>在已知 / 部分已知的电价、负载与光伏条件下，制定每日 0:00 起 144 个时段的购电计划，使全天（或全年）费用最小。</p>'+
      '<h3>2. 已知条件</h3><ul>'+
      '<li>储能：容量 12000 kWh，最大充放功率 5000 kW，效率 90%；</li>'+
      '<li>安全区间 [1200, 10800] kWh，0:00 与 24:00 均为 6000 kWh；</li>'+
      '<li>附件1 电价 / 负载 / 光伏预测；附件2 全年实际值；附件3 四时刻预报；附件4 分时电价。</li></ul>'+
      '<h3>3. 关键假设</h3><ul>'+
      '<li>储能自放电忽略，充放电效率恒为 90%；</li>'+
      '<li>光伏可全额消纳，允许弃电但不允许倒送；</li>'+
      '<li>紧急购电单价为计划电价的 5 倍。</li></ul>'+
      '<h3>4. 求解思路</h3><p>先建立单日 MILP 得到基准最优；再逐步放松"信息已知"的假设，依次叠加预测、滚动修正与电价不确定性，四个问题共用同一骨架。</p>'+
      '<div class="formula">min Σ p_t · g_t　　s.t.　g_t + PV_t + d_t = L_t + c_t,\nS_{t+1} = S_t + c_t − d_t / η,　1200 ≤ S_t ≤ 10800,　S_0 = S_144 = 6000</div>'+
      '<h3>5. 预期输出</h3><ul>'+
      '<li>逐时段购电量 / 充放电量 / 储电量（result1.xlsx）；</li>'+
      '<li>全年滚动结果（result2 / result3 / result4-2 / result4-3.xlsx）；</li>'+
      '<li>敏感性曲线与策略对比图。</li></ul>'+
      '</div></div></div>';
  }
  if(type==='animation'){
    /* ~接入改动：真实通道试过就交给它（含「生成中 / 没生成出来」的实话）*/
    var realAnim=animRealDrawerHTML();
    if(realAnim) return realAnim;
    var qs=animList();        /* 只含已生成的演示；未走到的步骤不显示 */
    var doneN=qs.length;
    var first=qs[0]||null;
    var cur=first?first.k:'0';
    ANIM_CUR=cur;
    return '<div class="card"><div class="card-h">'+ART.animation.icon.replace('#fff','#17A2C4')+'<h3>可视化演示</h3>'+
        '<span class="tag-c" style="font-size:11px">已生成 '+doneN+' / '+ANIM_CUR_SET.length+'</span><div class="grow"></div>'+
        '<button class="btn-ghost" onclick="toast(\'导出 HTML 为演示功能\')">导出 HTML</button></div>'+
      '<div class="card-b">'+
        '<div class="stage'+(first?'':' dim')+'" id="d-stage">'+(first?fsBtnHTML(cur)+animShotHTML(cur):'')+
          (first?'':'<div class="stage-empty">这里还没有演示内容<span>走到「全局分析」这一步后，会自动生成第一张可视化演示</span></div>')+'</div>'+
        '<div style="margin-top:14px">'+
          (first?'':'<div class="anim-empty">还没有演示。走到「全局分析」这一步后，演示一会出现在这里；之后每走到一个问题，就多一张演示。<br>未走到的步骤不会提前出现在列表里。</div>')+
          qs.map(function(q){
            return '<button class="acard'+(q.k===cur?' open':'')+'" id="dq-'+q.k+'" style="margin-bottom:8px" onclick="drawerAnim(\''+q.k+'\')">'+
              '<span class="ai" style="background:linear-gradient(120deg,#12A594,#1E9FC4)">'+q.k+'</span>'+
              '<span class="at"><b>'+esc(q.title)+'</b><span>'+esc(q.sub)+' · 点击回看</span></span></button>';
          }).join('')+
        '</div>'+
      '</div></div>';
  }
  if(type==='model'){
    return '<div class="card"><div class="card-h">'+ART.model.icon.replace('#fff','#F5A623')+'<h3>候选模型对比</h3></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr><th>方案</th><th>适用条件</th><th>严谨性</th><th>可扩展性</th><th>状态</th></tr></thead><tbody>'+
      '<tr style="background:#EFFAF8"><td><b>A. 混合整数线性规划</b></td><td>要求全局最优</td><td>★★★★★</td><td>★★★★★</td><td style="color:#12A594;font-weight:700" id="mdl-a">候选</td></tr>'+
      '<tr><td>B. 连续松弛 / Big-M</td><td>追求求解速度</td><td>★★★</td><td>★★★</td><td id="mdl-b">候选</td></tr>'+
      '<tr><td>C. 动态规划 / 随机规划</td><td>需要刻画不确定性</td><td>★★★★</td><td>★★★★</td><td id="mdl-c">候选</td></tr>'+
      '</tbody></table></div></div>'+
      '<div class="card"><div class="card-h"><h3>这一步由你决定</h3></div><div class="card-b">'+
      '<ul class="doc" style="padding:0;margin:0">'+
        '<li>左侧对话里的候选卡片都可以点，点「选它」之后我才生成对应版本的代码；</li>'+
        '<li>选定之后仍然可以反悔：直接说"换个模型再试"，我会按另一个方案重跑；</li>'+
        '<li>三个方案的结果会同步汇总到这里，方便你和论文里的对照。</li>'+
      '</ul>'+
      '<div class="note" style="margin-top:14px">平台不会替你自动选定模型 —— 数学建模里"为什么选这个模型"本身就是得分点。</div>'+
      '</div></div>';
  }
  if(type==='code'){
    /* ~接入改动：这一问拿到过真实源码包就展示它 */
    var realCode=codeRealBody(qi);
    if(realCode) return realCode;
    return '<div class="card"><div class="card-h">'+ART.code.icon.replace('#fff','#1E9FC4')+'<h3>main.py</h3><div class="grow"></div>'+
        '<button class="btn-ghost" onclick="toast(\'代码已复制\')">复制</button>'+
        '<button class="btn-ghost" onclick="toast(\'下载为演示功能\')">下载</button></div>'+
      '<div class="card-b" style="padding:0"><div class="code-box">'+
        '<div class="code-top"><span class="f">main.py · Python 3.10 · 78 行</span><div class="grow"></div>'+
        '<button onclick="toast(\'代码已复制\')">复制</button><button onclick="runCode(this)">运行</button></div>'+
        '<pre class="code">'+hl(PY_CODE_CUR)+'</pre>'+
        '<div class="run-out"><span class="lbl">RUN OUTPUT</span><span id="run-txt">$</span></div>'+
      '</div></div></div>';
  }
  if(type==='result'){
    if(state.detailId==='p5') return '<div class="kpis">'+
      '<div class="kpi"><div class="k">头部消费占比</div><div class="v">41.3%</div><div class="d">问题一 · 尾部 20 词占 6.8%</div></div>'+
      '<div class="kpi"><div class="k">分类稳健性</div><div class="v">±8%</div><div class="d">问题二 · 阈值 ±10% 扰动</div></div>'+
      '<div class="kpi"><div class="k">再分配提升</div><div class="v">↑</div><div class="d">问题三 · 总期望转化</div></div>'+
      '<div class="kpi"><div class="k">CVaR 区间</div><div class="v">90/95</div><div class="d">问题四 · 六项指标</div></div>'+
    '</div>'+
    '<div class="card"><div class="card-h"><h3>结果图</h3></div><div class="card-b">'+
      '<div class="stage">'+animShotHTML('2')+'</div>'+
      '<p class="side-note" style="margin-top:12px">图：关键词四象限分类散点（消费 vs 转化），四类用颜色区分。</p>'+
    '</div></div>'+
    '<div class="card"><div class="card-h"><h3>四问结果</h3></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr><th>问题</th><th>核心方法</th><th>关键参数</th><th>结果</th></tr></thead><tbody>'+
      [['问题一','效益率排序 + 时间均值','—','头部 20 词占 41.3%'],
       ['问题二','双阈值四象限分类','消费/转化分位数','四类成员变动 ±8%'],
       ['问题三','饱和响应 + KKT 再分配','τ_u, λ','总期望转化提升'],
       ['问题四','CVaR 约束 + 区间交付','置信 90%/95%','六项指标区间']].map(function(r){
        return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td></tr>';
      }).join('')+'</tbody></table></div></div>';
    return '<div class="kpis">'+
      '<div class="kpi"><div class="k">购电量</div><div class="v">59482.70</div><div class="d">kWh · 问题一</div></div>'+
      '<div class="kpi"><div class="k">购电费</div><div class="v">35126.95</div><div class="d">元 · 问题一</div></div>'+
      '<div class="kpi"><div class="k">储能充电</div><div class="v">20740.67</div><div class="d">kWh · 总放电 16799.94</div></div>'+
      '<div class="kpi"><div class="k">全年费用</div><div class="v">1369.18</div><div class="d">万元 · 问题二</div></div>'+
    '</div>'+
    '<div class="card"><div class="card-h"><h3>结果图</h3></div><div class="card-b">'+
      '<div class="stage">'+animSVG('2')+'</div>'+
      '<p class="side-note" style="margin-top:12px">图：全年滚动下计划费用与实际费用的累计曲线，两者的差距主要来自紧急购电。</p>'+
    '</div></div>'+
    '<div class="card"><div class="card-h"><h3>四问结果</h3></div>'+
      '<div style="overflow-x:auto"><table class="dtable"><thead><tr><th>问题</th><th>核心方法</th><th>最优参数</th><th>结果</th></tr></thead><tbody>'+
      [['问题一','单日 MILP','—','35126.95 元'],
       ['问题二','RF 预测 + 滚动 LP','α = 0.02','1369.18 万元'],
       ['问题三','多阶段滚动 + θ 触发','α = 0.01, θ = 20','1535.07 万元'],
       ['问题四-2','波动电价 + 滚动 LP','α 网格搜索','1471.70 万元'],
       ['问题四-3','波动电价 + 滚动修正','α = 0.02','1787.47 万元']].map(function(r){
        return '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td><td>'+r[2]+'</td><td>'+r[3]+'</td></tr>';
      }).join('')+'</tbody></table></div></div>'+
    '<div class="card"><div class="card-b">'+
      '<p style="font-size:13.4px;color:#4A5A70">确认结果可接受后，就可以在对话里让 AI 进入下一问。</p>'+
      '<div class="dl-row">'+
        '<button class="dl" onclick="toast(\'结果已导出\')">导出结果</button>'+
      '</div></div></div>';
  }
  if(type==='paper'){
    return '<div class="card"><div class="card-h">'+ART.paper.icon.replace('#fff','#12A594')+'<h3>论文初稿 · v1</h3><div class="grow"></div>'+
      '<button class="btn-ghost" onclick="toast(\'下载为演示功能\')">下载 .docx</button>'+
      '<button class="btn-ghost" onclick="toast(\'PDF 下载为演示功能\')">PDF</button></div>'+
      '<div class="card-b"><div class="doc" style="padding:0">'+
      '<h2>微网与外部电网电力调控策略研究</h2>'+
      '<div class="dc">22 页 · 图 15 张 · 表 8 张 · 参考文献 12 篇</div>'+
      '<h3>摘要</h3>'+
      '<p>针对小区微网在外部电网实时电价下的购电决策问题，本文以 2025 年逐日用能数据为基础，构建"预测 — 计划 — 滚动修正"的统一建模框架。首先建立含充放电互斥约束的混合整数线性规划模型，得到确定信息下的全天最优购电策略：总购电量 59482.70 kWh、总购电费 35126.95 元。进而引入随机森林负荷预测与三日滑动光伏预测，设计带安全裕量系数 α 的两阶段滚动线性规划，全年（334 天报告区间）总购电费降至 1369.18 万元，紧急购电占比 6.0%。进一步考虑每日四次光伏预报，建立带调整触发阈值 θ 的多阶段滚动修正模型，并刻画双向违约结算规则，全年总费用 1535.07 万元，紧急购电占比进一步降至 1.31%。最后在外网电价实时波动情形下重算，得到 1471.70 万元与 1787.47 万元两组结果，并发现安全裕量的最优取值随结算规则变化而发生反转。</p>'+
      '<h3>目录</h3>'+
      '<ul><li>一、问题重述与信息结构分析</li><li>二、数据预处理与探索性分析</li><li>三、问题一：确定信息下的单日 MILP 模型</li><li>四、问题二：预测驱动的两阶段滚动线性规划</li><li>五、问题三：四次预报下的多阶段滚动修正模型</li><li>六、问题四：电价不确定情形下的模型改造</li><li>七、灵敏度分析与稳健性检验</li><li>八、结论与改进方向</li><li>附录：代码与结果文件说明</li></ul>'+
      '<h3>第三章（节选）</h3>'+
      '<div class="formula">min　Σ_{t=1}^{144} p_t · g_t　　s.t.　g_t + PV_t + d_t = L_t + c_t,\nS_{t+1} = S_t + c_t − d_t / η,　1200 ≤ S_t ≤ 10800,　S_0 = S_144 = 6000</div>'+
      '<p>考虑到若不加充放电互斥约束，求解器会得到同一时段既充又放的假最优解（能量在 η ≠ 1 时被凭空放大），本文引入 0-1 变量 u_t，并配合单时段上限 833.33 kWh 建立紧的上界，保证所得解为全局最优……</p>'+
      '<div class="note">论文可以在平台上继续和 AI 交互修改：改摘要、补灵敏度、调排版、加图表说明都可以。</div>'+
      '</div></div></div>'+
      '<div class="card"><div class="card-b"><div class="dl-row" style="margin-top:0">'+
        '<button class="dl p" onclick="toast(\'下载为演示功能\')">下载论文初稿</button>'+
      '</div></div></div>';
  }
  return '<div class="card"><div class="card-b">暂无内容</div></div>';
}
/* ================= 我的成果 ================= */
export let openWorkId =null, miniTab={};

export function renderMine(){
  if(!WORKS.length){
    $('mine-list').innerHTML='<div class="card"><div class="empty">'+
      '<svg width="46" height="46" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'+
      '<h3>还没有生成任何成果</h3><p>去首页输入一道题目，10 分钟后你就会拥有第一篇论文初稿。</p></div></div>';
    return;
  }
  $('mine-list').innerHTML=WORKS.map(function(w){
    var on=!!w.share;
    return '<div class="wcard'+(openWorkId===w.id?' open':'')+'" id="wc-'+w.id+'">'+
      '<div class="wrow" onclick="toggleWork(\''+w.id+'\')">'+
        '<span class="wico">'+ART.paper.icon.replace('#fff','#12A594')+'</span>'+
        '<div class="wm">'+
          '<h3>'+esc(w.title)+
            '<span class="tag-c" style="font-size:11px">'+esc(w.tag)+'</span>'+
            (w.status==='已完成'
              ? '<span class="tag" style="padding:2px 8px;border-radius:6px;font-size:11px;background:#E9F7EF;color:#1E7A4F;font-weight:700">已完成</span>'
              : '<span class="tag" style="padding:2px 8px;border-radius:6px;font-size:11px;background:#FFF3E0;color:#B25E00;font-weight:700">进行中 '+w.steps+'/6</span>')+
          '</h3>'+
          '<div class="meta">'+
            '<span>'+ICON.guide.replace('width="16" height="16"','width="13" height="13"')+'更新于 '+esc(w.time)+'</span>'+
            '<span>模型：'+esc(w.models.join(' / '))+'</span>'+
            '<span>'+(openWorkId===w.id?'收起内容':'展开查看全部内容')+'</span>'+
          '</div>'+
        '</div>'+
        '<div class="switch-box" onclick="event.stopPropagation()">'+
          '<span class="st" id="share-state-'+w.id+'"><b>'+(on?'演示分享已开':'演示分享已关')+'</b>仅本次访问有效</span>'+
          '<button type="button" class="sw'+(on?' on':'')+'" role="switch" aria-checked="'+on+'" aria-label="演示分享：'+esc(w.title)+'" aria-describedby="share-state-'+w.id+'" onclick="event.stopPropagation();toggleShare(\''+w.id+'\',this)"></button>'+
        '</div>'+
      '</div>'+
      '<div class="wmore" id="wm-'+w.id+'" style="display:'+(openWorkId===w.id?'block':'none')+'">'+
        (openWorkId===w.id?miniHTML(w):'')+
      '</div>'+
    '</div>';
  }).join('');
}
export function toggleWork(id){
  if(openWorkId===id){ openWorkId=null; renderMine(); return; }
  openWorkId=id; renderMine();
  var el=$('wc-'+id);
  if(el) setTimeout(function(){ el.scrollIntoView({behavior:'smooth',block:'nearest'}); },60);
}
export function toggleShare(id,el){
  var w=WORKS.filter(function(x){return x.id===id})[0];
  if(!w || !el) return;
  w.share=!w.share;
  el.classList.toggle('on',w.share);
  el.setAttribute('aria-checked',String(w.share));
  var box=el.parentNode.querySelector('.st');
  if(box) box.innerHTML='<b>'+(w.share?'演示分享已开':'演示分享已关')+'</b>仅本次访问有效';
  toast('演示状态已'+(w.share?'开启':'关闭')+'，不会改变真实访问权限');
}
export function miniHTML(w){
  var t=miniTab[w.id]||'paper';
  var setter='setMiniTab(\''+w.id+'\',';
  return '<div class="wm-in">'+
    '<div style="display:flex;align-items:center;gap:12px;margin-bottom:14px;flex-wrap:wrap">'+
      '<b style="font-size:14px">成果全部内容</b>'+
      '<span style="font-size:12.5px;color:#8A9AAD">'+esc(w.paper)+'</span>'+
      '<div style="flex:1"></div>'+
      '<button class="btn-ghost" onclick="toast(\'导出为演示功能\')">导出全部</button>'+
      '<button class="btn-ghost" onclick="startWork(\''+esc(w.title).replace(/'/g,"\'")+'\',true)">在平台继续修改</button>'+
    '</div>'+
    '<div class="kpis" style="margin-bottom:16px">'+
      w.metrics.map(function(m){
        return '<div class="kpi"><div class="k">'+esc(m[0])+'</div><div class="v" style="font-size:20px">'+esc(m[1])+'</div></div>';
      }).join('')+
    '</div>'+
    '<div id="mini-dlv">'+dlvHTML(t,setter)+'</div>'+
  '</div>';
}
export function setMiniTab(wid,t){
  miniTab[wid]=t;
  var box=$('mini-dlv');
  if(box) box.innerHTML=dlvHTML(t,'setMiniTab(\''+wid+'\',');
}
/* ================= 初始化 ================= */
export function autoGrow(el){
  el.style.height='auto';
  el.style.height=Math.min(el.scrollHeight,200)+'px';
}
export function initPrototype(){
  var tabs=$('d-tabs').querySelectorAll('.tab');
  for(var i=0;i<tabs.length;i++){
    tabs[i].onclick=function(){ setDetailTab(this.getAttribute('data-tab')); };
  }
  var hi=$('home-input');
  hi.addEventListener('input',function(){ autoGrow(this); });
  hi.addEventListener('keydown',function(e){
    if(e.key==='Enter'&&!e.shiftKey){ e.preventDefault(); sendFromHome(); }
  });
  var wi=$('work-input');
  wi.addEventListener('input',function(){ autoGrow(this); });
  wi.addEventListener('keydown',function(e){
    if(e.key==='Enter'&&!e.shiftKey){ e.preventDefault(); sendInWork(); }
  });
  renderCarousel();
  renderFilters();
  renderGrid();
  startCarousel();
  /* 闸门看护常驻（见 ensureGateTick）：气泡定时器会随状态收尾而停，闸门不能跟着停 */
  ensureGateTick();
}

/* ⚠️ 这个 bridge 是模板字符串（反引号是它的结束符）：
 *    - 代码里要写反引号必须转义成 \`，注释里干脆别写 —— 顺手写一个就会把
 *      模板截断，报的是「Unexpected identifier」这种对不上号的语法错
 *    - 内联处理器里不要写 \' ，会被提前解义成 ' 而生成坏代码；参数走 data-*
 * ==================================================================
   迁移桥接层（非原型原码，本次新增）
   ================================================================== */

/** 演示模块已外置成 public/cases/<protoId>/anims/mod-N.html → 改用 iframe 挂载，
 *  CSS 与全局变量天然隔离（原型当年是靠 Shadow DOM + 代理 document 达到同样效果）。
 *  为什么不用原来的 Shadow DOM 方案：外置成独立页面后浏览器本身就提供了隔离，
 *  再套一层 Shadow DOM 只会让「按 F12 排查」变难。 */
export function mountAnim(k) {
  unmountAnim()
  const holder = $('afs-stage')
  if (!holder) return false
  const ifr = document.createElement('iframe')
  ifr.className = 'anim-frame'
  ifr.setAttribute('data-anim', String(k))
  /* ~接入改动：真实档位（智能体生成的动画）用 blob URL，内置档位照旧走 public/cases/<protoId>/anims/ */
  ifr.setAttribute('title', (animRealTitle(k) || animMeta(k)) + ' 可视化演示')
  ifr.setAttribute('src', animRealURL(k) || './cases/' + ANIM_DIR_CUR + '/anims/' + ANIM_PREFIX_CUR + k + '.html')
  holder.appendChild(ifr)
  ANIM_HOST = ifr
  return true
}

/* ── 沙箱衔接（非原型原码，本次新增）─────────────────────────────────────

   对话里代码生成好之后，用户可以选择把它丢进真沙箱去跑；跑完的产物再回到
   这条对话里。这两件事原型都没有，所以放在桥接层，规则与上面一致：
     - 按钮跟原型其它按钮一样走 inline onclick → 必须挂到 window（见下）
     - 跳转不在这里做 —— core 不认识路由，通过 hooks 交给接入方
     - 去程/回程的凭据格式见 data/runHandoff.ts
   注意：下面刻意不写内联处理器里的引号（让按钮从 data-* 读参数），
   因为这个 bridge 本身是模板字符串，写 \' 会被提前解成 ' 而生成出坏代码。 */

/** 「进入沙箱运行」按钮 —— 这道题在沙箱里没有环境时返回空串，按钮不出现 */
export function sandboxRunBtn(qn) {
  if (!sandboxReady()) return ''
  var q = QUIZ[qn] || {}
  return '<button class="abtn pri" onclick="openSandboxRun(' + qn + ')">进入沙箱运行（' + esc(q.codeName || 'main.py') + '）</button>'
}

/** inline onclick 的两个入口 —— 原型那套 HTML 只在 window 上找名字 */
export function openSandboxRun(qn) { gotoSandboxRun(qn) }
export function openSandboxArtifacts(sandboxId) { gotoSandboxArtifacts(sandboxId) }

/** 沙箱跑完的回执 → 一条对话消息：退出码 + 产物清单 + 回沙箱的入口 */
export function pushSandboxResult(rcp) {
  if (!rcp) return
  var files = rcp.files || []
  var fmt = function (n) {
    if (!n) return ''
    if (n < 1024) return n + ' B'
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
    return (n / 1048576).toFixed(1) + ' MB'
  }
  var list = files.length
    ? '<ul class="sb-files">' + files.map(function (f) {
        return '<li><code>' + esc(f.path) + '</code><span>' + fmt(f.size) + '</span></li>'
      }).join('') + '</ul>'
    : '<p>这一趟没有产出文件。</p>'
  var code = (rcp.exitCode === null || rcp.exitCode === undefined) ? '未知' : rcp.exitCode
  pushMsg({ who: 'ai', html:
    '<h4>沙箱运行完成 · ' + esc(rcp.command || 'main.py') + '</h4>' +
    '<p>退出码 <b>' + esc(String(code)) + '</b> · 产出 <b>' + files.length + '</b> 个文件。它们留在沙箱会话里，清单已经同步回这条对话。</p>' +
    list +
    '<div class="run-acts"><button class="abtn pri" data-sb="' + esc(rcp.sandboxId || '') + '" onclick="openSandboxArtifacts(this.dataset.sb)">在沙箱中查看产物</button></div>' +
    '<p class="next">下一步：告诉我要怎么解读这批结果，或者直接说「继续」推进流程。</p>'
  }, mkey())
}

/* ── 真实分析通道（非原型原码，本轮新增）─────────────────────────────────

   把 2026 国赛 C 题那条「分析 → 候选模型」从**写死的剧本**换成**智能体实时生成**。
   三道纪律：
     - 内置剧本一行不改：取不到真实内容时原样走它，它是永久的兜底
     - 页内标注来源：真实 / 内置要看得出来，不把生成内容说成预设内容
     - 只在这一层动手：core 段里的原型逻辑仍逐字未改（除几处标了「~接入改动」的开口）

   与沙箱那套的区别：沙箱解决「怎么跑代码」，这里解决「分析和候选从哪来」。 */

/** 四问的中文序号，用来拼「问题一」这种说法 */
function analysisCnAt(i){ return (QUIZ[i]&&QUIZ[i].cn)||String(i+1); }

/**
 * 这次工作台是「从详情页进来的」还是「从首页输入题目进来的」。
 *
 * 进工作台时由 analysisBoot 记下来，后面每一步都要用它 ——
 * 它决定题面取内置的还是取用户刚输入的那段。
 */
let analysisFromDetail=false;

/**
 * 开题那一刻的题目文件名列表（题面由文件承担的任务才有，刷新后由任务仓还原）。
 *
 * 附件暂存区是内存态、刷新即丢 —— 不把文件名记下来，「用题目文件开的题」刷新后
 * 就解不出题面（resolveCase 返回 null），对话区一句话都发不出去。文件名不变 ⇒
 * 算出的 caseId 不变 ⇒ 还能接回原会话。
 */
let analysisPaperNames=[];

/** 本道题在题库里的题号；只有从详情页进来时它才代表本题 */
function analysisProtoId(){ return analysisFromDetail?(state.detailId||''):''; }

/**
 * 当前这道题该用哪份题面（内置真题用内置题面，用户自己输入的题目用用户文本）。
 *
 * ⚠️ state.detailId 只在「从详情页进入」时才是本道题的题号。它是个全局状态，
 *    看完 C 题详情页再回首页输入自己的题时，它还留着 'p3' —— 无条件传进去
 *    会让内置 C 题题面顶掉用户刚输入的题面。所以只认 fromDetail 这一种情况。
 */
function analysisRef(title,fromDetail){
  var H=getAnalysisHooks(); if(!H) return null;
  var fd=!!fromDetail;
  /* 第 4 参 = 「题面由题目文件承担」：只有首页入口才可能走到这种情况
     （从详情页进来用的是题库里的内置题面）。flow 据此决定 caseId 与题面正文。
     第 5 参 = 刷新后的兜底：开题那一刻记下的题目文件名（暂存区已空时用它）。 */
  return H.resolveCase(fd?analysisProtoId():'', title||state.workTitle||'', fd, !fd&&attachHasProblem('home'), fd?[]:(analysisPaperNames||[]));
}

/**
 * 附件口径的实话。
 *
 * 三条：真传上去了就说「已上传、可读」；没传上去要把原因说出来（别让用户以为传了）；
 * 一个都没有就照旧写明「没有随请求提交」。含糊不得 —— 智能体那边「缺件即停」的
 * 硬约束靠的就是这个口径。
 */
function analysisAttachNote(ref){
  var H=getAnalysisHooks();
  var held=(H&&H.heldList)?H.heldList('home'):[];
  var up=0, wait=0, probUp=0, dataUp=0;
  for(var i=0;i<held.length;i++){
    var isP=held[i].kind==='problem';
    if(held[i].uploaded){ if(isP) probUp++; else dataUp++; }
    else wait++;
  }
  up=probUp+dataUp;
  var fail=(H&&H.attachFailureText)?(H.attachFailureText()||''):'';
  var bits=[];
  if(wait) bits.push('正在把你选的 '+wait+' 个文件传到本题会话…');
  if(probUp) bits.push('题目文件'+(probUp>1?'（'+probUp+' 份）':'')+'已上传到本题会话，智能体按它来理解题目。');
  if(dataUp) bits.push('另有 '+dataUp+' 个数据附件已上传到本题会话工作区，智能体可以直接读取。');
  if(ref&&!ref.text) bits.push('这次没有在输入框写题面，题目内容以题目文件为准 —— 智能体会先读它。');
  if(fail) bits.push('有文件没能传上去：'+fail+'。');
  if(!up&&!wait&&!fail) bits.push('本次没有附件随请求提交；题面里若点名了附件，智能体会按「未读取」如实登记，不会凭文件名推测里面的数据。');
  return '<p class="src-note">'+esc(bits.join(''))+'</p>';
}

/**
 * 读题气泡的**头部**：标题 + 附件口径 + 进度 + 跳出口。
 *
 * ⚠️ **实时对话区不在这里** —— 它由旁边那个 .lv-stream div 单独承载。
 * 为什么拆成两个兄弟节点：头部每隔 700ms 整体重写一次（进度在变），如果实时区
 * 也在这个字符串里，每重写一次就把智能体刚画上去的思考/工具块冲掉一次。
 *
 * ⚠️ **进度行里不带秒数**（2026-10-13 改）：analysisHeadHTML 现在只出**模具**，
 * .an-meter 是空的，秒数由 paintText 走 textContent 补。原因：头部每 700ms
 * 重写一次，而秒数每秒都在变 —— 秒数留在字符串里，等值守卫就永远不成立，
 * 头部连同里面的「跳过等待」按钮每秒被重建一次（按钮的监听器随即失效）。
 */
function analysisHeadHTML(ref){
  return '<h4>正在读题并生成分析</h4>'+
    '<p>智能体正在对<b>'+esc(ref.title||'这道题')+'</b>做题意分析与建模路线设计，一次跑完四个问题；跑完我会把真实分析接到下面的流程里。</p>'+
    analysisAttachNote(ref)+
    '<p class="an-meter"></p>'+
    '<div class="run-acts"><button class="abtn gh an-skip">跳过等待</button></div>';
}

/** 头部进度行这一刻的文字（秒数在这里、不在模具里） */
function analysisMeterText(){
  var H=getAnalysisHooks();
  var st=H?H.status():'idle';
  if(st!=='running') return '正在准备工作区…';
  return (H&&H.progressText&&H.progressText())||'';
}

/**
 * 只改文本节点，**不重建 DOM**。
 *
 * 秒数这类「每秒都在变、但结构没变」的内容走这里：整块 innerHTML 重写会把
 * 子节点全部换掉（转圈动画重头转、按钮监听器失效），而 textContent 是原地更新。
 */
function paintText(root,sel,text){
  if(!root) return;
  var n=root.querySelector(sel);
  if(n&&n.textContent!==text) n.textContent=text;
}

/**
 * 头部只重写模具、不重写进度：模具与时间无关 ⇒ 只有「工具调用次数 / 最近工具 /
 * 附件口径 / 状态」这些**真变了**的时候才动 DOM。
 */
function syncHead(el,ref){
  if(!el) return;
  var head=el.querySelector('.an-head');
  if(!head) return;
  var mold=analysisHeadHTML(ref||{});
  if(head.getAttribute('data-mold')!==mold){
    head.innerHTML=mold;
    head.setAttribute('data-mold',mold);
  }
  paintText(head,'.an-meter',analysisMeterText());
}

/** 实时对话区的挂载点 —— 智能体的思考 / 工具调用 / 正文都画进这里 */
function liveBoxHTML(){ return '<div class="lv-stream"></div>'; }

/* ==================================================================
   产物卡（Step3，2026-10-09）

   跑完分析后往对话区追一张「智能体产物」卡：列出本会话 + 全部子会话
   artifacts/ 下的**全部**文件（先不过滤），点一张进右侧抽屉看文本。

   两个纪律：
     · **不写内联 onclick**（bridge 是模板字符串，内联处理器里的引号会在生成期
       被提前解义），参数一律走 data-* + 事件委托；
     · **事件委托挂在外层容器上**：卡片墙是整块 innerHTML 重画的（点一次「刷新」
       就重画一次），直接绑在卡片上的监听器会随节点一起消失，挂容器不会。
   ================================================================== */

/** 产物卡的容器（内部由 analysis 模块整块重画） */
function artifactCardBoxHTML(){ return '<div class="art-card"></div>'; }

/** 产物卡的事件委托：刷新按钮 / 点卡片看内容 */
function bindArtifactCard(box,H,caseId){
  box.addEventListener('click',function(ev){
    var t=ev.target;
    /* 从点击处往上找带 data-* 的那个节点（卡片是 button，刷新按钮也在里面） */
    while(t && t!==box && !(t.getAttribute && (t.hasAttribute('data-path')||t.hasAttribute('data-art-refresh')))) t=t.parentNode;
    if(!t || t===box || !t.getAttribute) return;
    if(t.getAttribute('data-art-refresh')!==null){ H.artifactRefresh(box,caseId); return; }
    var p=t.getAttribute('data-path'); if(!p) return;
    H.artifactPreview(p,box,caseId).then(function(r){
      if(r) openArtifactView(r.title,r.sub,r.html);
    }).catch(function(){});
  });
}

/**
/** 产物区 */
function artifactCardBoot(caseId){
  var H=getAnalysisHooks();
  if(!H||!H.artifactMount) return;
  var host=railArtHost(true);
  if(!host) return;                      /* 不在工作台视图 / 右栏不在：不画，也不回退到对话区 */
  var box=host.querySelector('.art-card');
  if(!box){
    box=document.createElement('div');
    box.className='art-card';
    host.appendChild(box);
  }
  if(box.getAttribute('data-bound')!=='1'){
    box.setAttribute('data-bound','1');
    bindArtifactCard(box,H,caseId);
  }
  H.artifactMount(box,caseId);
  var p=H.artifactRefresh(box,caseId);
  if(p&&p.then) p.then(function(){ railArtCount(box); });
  railTabBadgeRefresh();
}

/* ==================================================================
   分析报告卡

   「以 docx 为主，动画以 html」：分析跑完后，把产物里的分析主稿整理成 Word
   交到对话区（挑文件 + 调后端导出 + 下载全在 analysis 模块里）。动画产物是
   HTML，本来就能在浏览器里直接跑，不进这条路径。

   与产物卡同构，刻意**各占一条气泡**：产物卡是「清单」，报告卡是「这一份成品」，
   混在一起用户分不清哪个是能直接交出去的东西。
   ================================================================== */

/** 报告卡的容器（内部由 analysis 模块整块重画） */
function reportCardBoxHTML(){ return '<div class="rep-card"></div>'; }

/** 报告卡的事件委托：重新生成 / 下载 Word / 预览原稿 / 重试 */
function bindReportCard(box,H,caseId){
  box.addEventListener('click',function(ev){
    var t=ev.target;
    while(t && t!==box && !(t.getAttribute && t.hasAttribute('data-report'))) t=t.parentNode;
    if(!t || t===box || !t.getAttribute) return;
    var act=t.getAttribute('data-report');
    if(act==='download'){ H.reportDownload(box,caseId); return; }
    if(act==='preview'){
      H.reportPreview(box,caseId).then(function(r){
        if(r) openArtifactView(r.title,r.sub,r.html);
      }).catch(function(){});
      return;
    }
    /* refresh 与 retry 同一动作：重新走一遍「找主稿 → 导出」 */
    H.reportRefresh(box,caseId);
  });
}

/** 往对话区追一张分析报告卡 */
function reportCardBoot(caseId){
  var H=getAnalysisHooks();
  if(!H||!H.reportMount) return;
  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+reportCardBoxHTML()+'</div></div>';
  workHost().appendChild(el);
  var box=el.querySelector('.rep-card');
  if(!box) return;
  bindReportCard(box,H,caseId);
  H.reportMount(box,caseId);
  H.reportRefresh(box,caseId);
  scrollBottomIfStick();
}

/**
 * 运行结束后把气泡**定格**成一条运行记录。
 * @returns true = 已定格；false = 已删除
 */
function sealRunBubble(el,liveBox,H){
  /* 认这个气泡自己的实时容器 —— 任务仓把对话分区之后，「当前容器」可能已经是
     别条任务的了，问全局状态会答错 */
  var keep=H.liveActive?H.liveActive(liveBox):false;
  /* 气泡里挂着闸门卡就不能删：智能体只发了思考/工具、还没写正文时 liveActive 为假，
     这时把气泡删掉等于把用户唯一能填表的入口删掉 —— 那条 run 会永远挂着 */
  if(!keep && el.querySelector('.gate-card')) keep=true;
  if(!keep){ el.remove(); return false; }
  if(H.liveRender) H.liveRender(liveBox);
  var head=el.querySelector('.an-head');
  /* ~接入改动（2026-10-08）：分析过程不再上屏（思考 / 工具调用只留一个转圈），
     所以定格后这条气泡里只剩智能体说的话 —— 头部那几行读题进度收尾后没有意义，
     直接清掉，免得一条「正文」气泡还顶着「正在读题并生成分析」的标题 */
  if(head) head.innerHTML='';
  return true;
}

/* ==================================================================
   智能滚动
   ================================================================== */
function stickEl(){
  var st=$('stream');
  if(!st) return null;
  if(!st.__smyyStickBound){
    st.__smyyStickBound=true;
    st.addEventListener('scroll',function(){
      var top=st.scrollTop;
      if(typeof st.__smyyTop==='number' && top<st.__smyyTop-4) st.__smyyStick=false;
      else if(st.scrollHeight-top-st.clientHeight<120) st.__smyyStick=true;
      st.__smyyTop=top;
    },{passive:true});
  }
  return st;
}
function scrollBottomIfStick(){
  var st=stickEl();
  if(!st) return;
  if(st.__smyyStick===false) return;   /* 用户正在看前面的内容，别动他的视口 */
  st.scrollTop=st.scrollHeight;
  st.__smyyStick=true;
  st.__smyyTop=st.scrollTop;
}

/*
   闸门卡片
/** 字段初始值 → 文本 */
function gateInitText(f){
  var v=f&&f.initialValue;
  if(v===undefined||v===null) return '';
  if(Array.isArray(v)) return '';
  return String(v);
}
/** 这个选项是不是初始选中的（多选看数组，单选看相等） */
function gateInitChecked(f,option){
  var v=f&&f.initialValue;
  if(Array.isArray(v)) for(var i=0;i<v.length;i++) if(String(v[i])===String(option)) return true;
  return v!==undefined&&v!==null&&String(v)===String(option);
}

/**
 * 一个字段的 HTML。所有控件都带 data-key，提交时按它取值。
 * ⚠ 本函数整段位于生成器的**模板字符串**里：注释与字符串里一律不能出现反引号，
 *   否则模板串会被提前截断（写这段时踩过一次）。
 */
function gateFieldHTML(f,gateId){
  var head='<span>'+esc(f.label||f.key)+(f.required?'<em>*</em>':'')+'</span>';
  var attrs=' data-key="'+esc(f.key)+'"'+(f.required?' data-req="1"':'');
  var i,o;
  if(f.kind==='radio-group'||f.kind==='checkbox-group'){
    var type=f.kind==='checkbox-group'?'checkbox':'radio';
    var nameAttr=type==='radio'?' name="'+esc((gateId||'gate')+'-'+f.key)+'"':'';
    var opts='';
    for(i=0;i<f.options.length;i++){
      o=f.options[i];
      opts+='<label class="gate-opt'+(gateInitChecked(f,o)?' on':'')+'"><input type="'+type+'"'+nameAttr+attrs+
        ' value="'+esc(o)+'"'+(gateInitChecked(f,o)?' checked':'')+'><span>'+esc(o)+'</span></label>';
    }
    return '<label class="gate-field">'+head+'<div class="gate-opts">'+opts+'</div></label>';
  }
  if(f.kind==='select'){
    var list='<option value="">请选择</option>';
    for(i=0;i<f.options.length;i++){
      o=f.options[i];
      list+='<option value="'+esc(o)+'"'+(gateInitText(f)===o?' selected':'')+'>'+esc(o)+'</option>';
    }
    return '<label class="gate-field">'+head+'<select'+attrs+'>'+list+'</select></label>';
  }
  if(f.kind==='textarea'){
    return '<label class="gate-field">'+head+'<textarea rows="3"'+attrs+
      ' placeholder="'+esc(f.placeholder||'')+'">'+esc(gateInitText(f))+'</textarea></label>';
  }
  var input='<input type="'+(f.kind==='number'?'number':'text')+'"'+attrs+
    (f.kind==='number'?' data-num="1"':'')+
    (typeof f.min==='number'?' min="'+f.min+'"':'')+
    (typeof f.max==='number'?' max="'+f.max+'"':'')+
    (typeof f.step==='number'?' step="'+f.step+'"':'')+
    ' value="'+esc(gateInitText(f))+'" placeholder="'+esc(f.placeholder||'')+'">';
  return '<label class="gate-field">'+head+input+'</label>';
}

/** 整张卡片的 HTML */
function gateCardHTML(g){
  var fields='';
  for(var i=0;i<g.fields.length;i++) fields+=gateFieldHTML(g.fields[i],g.interruptId);
  return '<div class="gate-card" data-gate="'+esc(g.interruptId)+'">'+
    '<div class="gate-t"><i></i>'+esc(g.title)+'</div>'+
    (g.message?'<p class="gate-m">'+esc(g.message)+'</p>':'')+
    '<div class="gate-f">'+fields+'</div>'+
    '<div class="gate-acts">'+
      '<button class="abtn gh" data-act="cancel">取消</button>'+
      '<button class="abtn pri" data-act="submit">'+esc(g.submitLabel)+'</button>'+
    '</div>'+
    '<p class="gate-note">填完点提交，智能体会接着往下做 —— 它是在等你这一步。</p>'+
  '</div>';
}

/** 从卡片上读出用户填的值（多选是数组，数字转 Number） */
function gateReadValues(card){
  var out={}, nodes=card.querySelectorAll('[data-key]');
  for(var i=0;i<nodes.length;i++){
    var el=nodes[i], k=el.getAttribute('data-key');
    if(!k) continue;
    if(el.type==='checkbox'){
      if(!Array.isArray(out[k])) out[k]=[];
      if(el.checked) out[k].push(el.value);
    }else if(el.type==='radio'){
      if(el.checked) out[k]=el.value;
    }else if(el.getAttribute('data-num')==='1'){
      out[k]=el.value===''?'':Number(el.value);
    }else{
      out[k]=el.value;
    }
  }
  return out;
}

/** 必填项为空就拦下来 —— 提交一份缺字段的答复，智能体只能再来问一次 */
function gateMissing(card){
  var nodes=card.querySelectorAll('[data-req="1"]');
  for(var i=0;i<nodes.length;i++){
    var el=nodes[i], k=el.getAttribute('data-key');
    if(el.type==='checkbox'){
      var picked=card.querySelectorAll('input[type=checkbox][data-key="'+k+'"]:checked');
      if(!picked.length) return k;
    }else if(el.type==='radio'){
      if(!card.querySelector('input[type=radio][data-key="'+k+'"]:checked')) return k;
    }else if(!String(el.value||'').trim()){
      return k;
    }
  }
  return '';
}

/* ── 提交回执───────────────────────────────────────
/** 这张卡还在跟用户对话（提交中 / 刚失败）—— 轮询不许把它收掉，见 syncGate 的判据 */
function gateSticky(card){
  return card.getAttribute('data-busy')==='1'||card.classList.contains('fail');
}

/**
 * 把一张卡从右栏**整块**摘掉（连 `.rail-gate-card` 外壳一起）。
 * 只 remove 内层 `.gate-card` 会留下一串空壳：右栏越换越多、徽标数跟着虚高。
 */
function dropGateCard(card){
  var wrap=(card&&card.closest)?card.closest('.rail-gate-card'):null;
  (wrap||card).remove();
}

/** 提交/取消已经发出去了、还没回执：按钮禁用 + 如实说明在等后端 */
function gateBusy(card,note){
  card.setAttribute('data-busy','1');
  var btns=card.querySelectorAll('.gate-acts button');
  for(var i=0;i<btns.length;i++) btns[i].disabled=true;
  var el=card.querySelector('.gate-note');
  if(el) el.textContent=note;
}

/**
 * 等一次提交/取消的回执，再决定卡片是定格还是报错。
 *
 * 钩子没实现（单独跑原型、脚本里没有 Vue 外壳）时可能拿到 undefined —— 按
 * 「受理了」处理：本地 resolve 那条老路没有失败可言，不能把正常收尾打成失败。
 */
function gateSettle(card,p,okNote){
  if(!p||typeof p.then!=='function'){ gateFreeze(card,okNote); return; }
  p.then(function(r){
    if(r&&r.ok===false){ gateFail(card,r.reason||'提交没有成功'); return; }
    gateFreeze(card,okNote);
  },function(e){
    gateFail(card,(e&&e.message)?e.message:'提交失败（前端异常）');
  });
}

/**
 * 提交失败：卡片**不定格**、恢复成可交互，把原因摊在卡片上。
 *
 * 恢复可交互是关键 —— 这次失败多半是「这条会话上还挂着别的未决中断」这类可以
 * 当场重试的情形，把卡片锁死等于逼用户刷新整页。
 */
function gateFail(card,reason){
  card.removeAttribute('data-busy');
  card.classList.add('fail');
  var btns=card.querySelectorAll('.gate-acts button');
  for(var i=0;i<btns.length;i++) btns[i].disabled=false;
  var el=card.querySelector('.gate-note');
  if(el) el.textContent='没提交成功：'+reason;
  railGateReopen(card);
  toast('闸门没提交成功，请看右侧卡片上的原因',IC_WARN);
}

/** 提交失败后把右栏那张卡放回待办（railGateDecide 的反向动作） */
function railGateReopen(card){
  var wrap=(card&&card.closest)?card.closest('.rail-gate-card'):null;
  if(wrap) wrap.classList.remove('decided');
  railGateBadge();
}

/** 提交/取消后把卡片定格：留在右栏当记录，不再被轮询重画 */
function gateFreeze(card,note){
  card.className='gate-card done';
  card.setAttribute('data-locked','1');
  card.removeAttribute('data-busy');
  var old=card.querySelector('.gate-note');
  if(old) old.textContent=note;
  /* 2026-10-20：定格这一刻把「问了什么 / 选了什么」抄进闸门记录 ——
     新闸门一到这张卡会被 dropGateCard 顶掉，不抄下来用户就再也看不到它了 */
  gateHistPush(card,note);
  /* ~接入改动（2026-10-12）：右栏那张卡片跟着定格成「已确认」，并从待办数里划掉 */
  railGateDecide(null,card);
}

/** 一个字段的选项描边跟着勾选状态重画（change 与「撤选」两处共用这一份） */
function paintGateOpts(card,key){
  if(!key) return;
  var group=card.querySelectorAll('input[data-key="'+key+'"]');
  for(var i=0;i<group.length;i++){
    var lab=group[i].closest('.gate-opt');
    if(lab) lab.className='gate-opt'+(group[i].checked?' on':'');
  }
}

/** 该单选字段当前选中的值（组内没有 checked 的就返回空串） */
function gateGroupPick(card,key){
  var group=card.querySelectorAll('input[data-key="'+key+'"]');
  for(var i=0;i<group.length;i++){
    if(group[i].type==='radio'&&group[i].checked) return group[i].value;
  }
  return '';
}

function bindGateCard(card,g,H){
  /* 选中态描边（不用 :has()，老一点的浏览器也能对上） */
  card.addEventListener('change',function(ev){
    var t=ev.target;
    if(!t||!t.getAttribute) return;
    var key=t.getAttribute('data-key');
    paintGateOpts(card,key);
    if(key) card.__pick[key]=gateGroupPick(card,key);
    card.__optGuard=null;
  });
  /* 本组当前选中的值 —— 「点第二下撤选」靠它判断，不靠浏览器在 mousedown/click
     哪个时刻改 checked（各家实现不一致，实测点圆点与点文字的反应就不一样）。
     绑定时先按初始值灌一遍，否则「初始就有选中项」的卡片点它撤不掉。 */
  card.__pick={};
  card.__optGuard=null;
  (function(){
    var all=card.querySelectorAll('input[data-key]');
    for(var i=0;i<all.length;i++){
      var k=all[i].getAttribute('data-key');
      if(k&&all[i].type==='radio') card.__pick[k]=gateGroupPick(card,k);
    }
  })();
  /* 每次真实点击都重置「本次点击已处理」标记 */
  card.addEventListener('mousedown',function(){ card.__optGuard=null; });
  card.addEventListener('click',function(ev){
    var t=ev.target;
    if(!t||!t.tagName) return;
    /* 单选组：再点一次已选中的那项 = 撤销这一选（原生 radio 给不了「都不选」这个状态，
       用户 2026-10-13 报「选项选错了退不回去」）。preventDefault 是必须的 ——
       不压住默认动作，checked 会立刻被设回 true。
       两处防抖：① 点 label 里的文字时浏览器会把这次点击再转发给 input，同一串
       点击里第二次进来直接压住（__optGuard 认的是那整个 label）；② 撤选后本组
       选中值要同步清空，否则下次想再点亮这一项会被当成「又点了已选中项」。 */
    var lab=t.closest?t.closest('.gate-opt'):null;
    if(lab){
      var inp=lab.querySelector('input');
      if(inp&&inp.type==='radio'){
        var key=inp.getAttribute('data-key')||'';
        if(card.__optGuard===lab){ ev.preventDefault(); return; }
        if(card.__pick[key]===inp.value){
          /* 撤选要**做两遍**：radio 的勾选由浏览器的默认动作完成，而「点圆点本身」时
             Chrome 在 click 之前就把 checked 设好了，preventDefault 压不住那一步 ——
             实测点圆点：handler 里明明设了 false，事件跑完它又是 true。所以除了当场
             撤销，再在下一个 tick（默认动作已完成）撤一次，两种时序都覆盖到。 */
          var undo=function(){ inp.checked=false; paintGateOpts(card,key); };
          ev.preventDefault();
          undo();
          window.setTimeout(undo,0);
          card.__pick[key]='';
          card.__optGuard=lab;
          return;
        }
      }
    }
    if(t.tagName!=='BUTTON') return;
    var act=t.getAttribute('data-act');
    if(!act) return;
    if(act==='cancel'){
      gateBusy(card,'取消中…');
      gateSettle(card,H.gateCancel(),'已取消这次确认。可以重新发一次题，或在工作台里继续追问。');
      return;
    }
    if(act!=='submit') return;
    var missing=gateMissing(card);
    if(missing){ toast('有必填项还没填：'+missing,IC_WARN); return; }
    var values=gateReadValues(card);
    /* ~手改产物（2026-10-16，生成器崩在 build-port.mjs 3057「live is not defined」，
       改不进规则，见 topics/smyy-原型与沙箱.md）：先报「提交中」，拿到回执再定格。
       早先是同步 gateFreeze('已提交…') —— 后端那边失败（哪怕一个请求都没发出去，
       例如重建闸门拿不到 runId 就提前 bail）卡片也照样写着「已提交」，用户零线索。 */
    gateBusy(card,'提交中…');
    gateSettle(card,H.gateSubmit(values),'已提交，智能体继续处理…');
  });
}

/**
 * 对账式挂闸门：每 700ms 看一眼「这条气泡所属的那次 run 有没有挂起的闸门」。
 * 有就画卡（已画过同一张就跳过），没有就把还没提交过的卡片收掉。
 *
 * 2026-10-12 改道：卡片落到**右栏闸门区**（railGateHost()），对话流里不再出现。
 * 右栏不在（单跑原型 / 脚本，没有 Vue 外壳）时回退老路径 —— 挂进等待气泡。
 */
function syncGate(host,H){
  var rail=railGateHost();
  /* 全局 tick 用 host=null 调进来（右栏才是常驻宿主）。不在工作台视图时右栏为 null，
     早先直接 (rail||host).querySelector 会抛 TypeError，把整条重绘链打断。 */
  if(!rail&&!host) return;
  var box=(rail||host).querySelector('.gate-card');
  var g=H.gate?H.gate():null;
  /* 「是不是同一张卡」不能只看 data-gate：重建路径可能拿不到中断 id（空串），
     两张空 id 的卡会被判成同一张，新闸门就再也画不出来。id 为空一律算不同。 */
  var same=!!(box&&g&&g.interruptId&&box.getAttribute('data-gate')===g.interruptId);
  /* ~手改产物（2026-10-16，生成器崩在 build-port.mjs「live is not defined」）：
     提交那一刻槽位就清空了（state.gate=null），照旧判据这张卡会在 700ms 内被删掉 ——
     用户点完提交、卡片凭空消失，成功失败都看不出来。故 in-flight / 失败两种卡另判。 */
  /* 提交在途（data-busy）的卡谁也别动 —— 回执还没回来，挪走等于把结果藏了 */
  if(box&&box.getAttribute('data-busy')==='1') return;
  /* 定格卡（已提交/已取消）与刚失败的卡，**只在这张就是当前闸门（或压根没闸门）时**
     才留着当记录。早先定格卡是无条件 return —— 提交过一次，右栏那张卡就锁死在那儿
     （按钮也禁用了），后面所有新闸门都画不出来：智能体说「已重渲染，等你提交」，
     用户看到的是一张点不动的旧卡（报的就是「没有反应」）。 */
  if(box&&gateSticky(box)&&(!g||same)) return;
  if(box&&box.getAttribute('data-locked')==='1'&&(!g||same)) return;
  if(!g){ if(box) dropGateCard(box); railGateBadge(); return; }
  if(same) return;
  if(box) dropGateCard(box);
  var wrap=document.createElement('div');
  wrap.innerHTML=gateCardHTML(g);
  var card=wrap.firstChild;
  if(rail){
    wrap.className='rail-gate-card';
    rail.appendChild(wrap);
    rail.scrollTop=rail.scrollHeight;
  }else if(host){
    /* 回退老路径：挂进气泡的**内容流**（.txt），不能挂在 .msg 上 —— 那个是
       flex 行（头像 + 气泡），挂上去卡片会跑到气泡**右边**去。
       两处都没有（比如还没切到工作台）就什么都不挂 —— 早先直接 appendChild 会抛
       TypeError，把 700ms 的轮询整条打断。 */
    (host.querySelector('.txt')||host).appendChild(card);
  }
  bindGateCard(card,g,H);
  if(rail){ railGateBadge(); railExpand(); railTabSoft('gate'); }
  scrollBottomIfStick();
}

/* ~手改产物（2026-10-19）：闸门重绘的第二个驱动源。
 *
 * 原先右栏闸门只由那条分析气泡的 700ms 定时器画（见 finish 里的 clearInterval）：
 * status 一变 done/failed 它就把自己清掉，之后再重建出来的闸门**没有任何东西**
 * 会把它画出来 —— 用户看到右栏还是上一张卡，读作「智能体说重渲染了闸门，我没反应」。
 * 这一条与气泡无关，进页面起一次就常驻；syncGate 自带 same 判据，两边同时调也不会重画。 */
var gateTick=null;
function ensureGateTick(){
  if(gateTick) return;
  gateTick=setInterval(function(){
    var H=getAnalysisHooks();
    if(!H||!H.gate) return;
    syncGate(null,H);
  },700);
}

/**
 * 六步进度条。
 */
function syncSteps(){
  var H=getAnalysisHooks();
  if(!H||!H.step) return;
  var n=Number(H.step())||0;
  if(n>curStep) renderSteps(n);
}

/**
 * 进工作台时的读题闸门。
 *
 * 一次 run 覆盖四个问题，所以只在入口等一次；后面每一步读的都是同一份缓存，
 * 不会再等第二次。失败 / 超时 / 用户点跳过，都直接放行走内置剧本。
 */
export function analysisBoot(title,fromDetail,cb){
  analysisFromDetail=!!fromDetail;   /* 后面每一步都要用它决定题面来源 */
  var H=getAnalysisHooks();
  if(!H){ cb(); return; }
  var ref=analysisRef(title,fromDetail);
  if(!ref){ cb(); return; }

  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+
    '<div class="an-head">'+analysisHeadHTML(ref)+'</div>'+
    liveBoxHTML()+
    '</div></div>';
  workHost().appendChild(el);
  scrollBottom();
  syncHead(el,ref);   /* 进度行首帧：秒数走 textContent，不进模具 */

  var liveBox=el.querySelector('.lv-stream');
  if(H.liveMount) H.liveMount(liveBox);

  var done=false, timer=null;
  function finish(){
    if(done) return; done=true;
    if(timer) clearInterval(timer);
    sealRunBubble(el,liveBox,H);
    cb();
    /* ~接入改动（2026-10-09，P2；2026-10-10 调序）：先追「分析报告」卡（成品，能直接
       下载 Word）再追「智能体产物」卡（清单）。理由与站点一贯的口径一致：成品先行、
       明细在后 —— 产物清单可能很长，成品压在它下面要滚半天才看得见。 */
    reportCardBoot(ref.caseId);
    /* ~接入改动（2026-10-09）：分析跑完（或接回已有产物）后追一张产物清单卡。
       放在 cb() 之后 —— cb 里还会往对话区推分析/候选模型那几条，这些卡排在它们后面。
       会话坐标在挂载那一刻冻结（ref.caseId），切到别的任务后点刷新也不会读错会话。 */
    artifactCardBoot(ref.caseId);
  }
  /* 事件委托，**不绑在按钮节点上**（2026-10-13）：头部现在有了等值守卫、不再
     每秒重建，但状态一变还是可能重写 —— 绑节点上的监听器会随之一并消失。
     挂在外层容器上就与重写无关了。 */
  el.addEventListener('click',function(ev){
    var t=ev.target;
    if(t&&t.closest&&t.closest('.an-skip')) H.skip();
  });

  /* 刷新后产物可能已经在会话里 —— 先试读；读到就不烧模型调用 */
  H.reuse(ref).then(function(ok){
    if(done) return;
    if(ok) finish(); else H.start(ref);
  }).catch(function(){ if(!done) H.start(ref); });

  timer=setInterval(function(){
    var st=H.status();
    /* 头部：只重写模具（与时间无关），秒数走 paintText；实时区原样累加 */
    if(!done) syncHead(el,ref);
    if(H.liveRender) H.liveRender(liveBox);
    syncGate(el,H);
    syncSteps();
    /* 跟随期间新落盘的产物要跟手出现在清单里（内部按签名去重，没变就不打接口） */
    refreshArtCard();
    attachStripSync('home');
    attachStripSync('work');
    /* 粘底才滚 —— 用户往上翻看前面的内容时不许把他拽回来 */
    scrollBottomIfStick();
    if(st==='done'||st==='failed'){
      if(st==='failed'){
        /* 内置剧本已删（2026-10-08）：拿不到就如实说原因，不再拿写死内容顶上 */
        var why=H.failureText()||'';
        pushMsg({who:'ai',html:'<h4>智能体这次没有返回分析结果</h4>'+
          '<p class="src-note">这一步的内容由智能体实时生成，本次没拿到。你可以在下方输入框里追问，或重新发一次题。</p>'+
          (why?'<p class="src-note">原因：'+esc(why)+'</p>':'')},mkey());
      }else{
        /* 2026-10-20（用户报「子问题结束之后没有回应，我一直在等」）：全局分析跑完
           必须**明确交棒**。早先这里什么都不说，气泡一收尾页面上就再没有任何动静，
           用户不知道「已经做完了、下一步该我说一句」。 */
        pushMsg({who:'ai',html:'<h4>全局分析已完成</h4>'+
          '<p>四问的思路、假设与模型路线都写好了，文件在右侧「产物」里（<b>全局分析</b>）。</p>'+
          '<p class="next">我停在这里等你发话 —— 前端不会自己往下跑：</p>'+
          '<div class="quick-row">'+
          '</div>'},mkey());
        setPhase('needNext');
      }
      finish();
    }
  },700);
}

/** 代码那一步的来源说明：分析由智能体生成，代码这一步仍走内置通道 */
export function analysisCodeNote(){
  var H=getAnalysisHooks(); if(!H) return '';
  return H.codeNote()||'';
}

/** 产物抽屉里的真实文档；没有真实产物返回空串，抽屉回落到原有内容 */
export function analysisLiveBody(type){
  var H=getAnalysisHooks(); if(!H) return '';
  return H.liveDoc(type)||'';
}

/* ── 附件真上传（非原型原码，本轮新增）─────────────────────────────────────

   把首页与工作台那两个「上传」按钮接成真上传。分工：
     -    校验与暂存交给 flow（只有它知道 caseId 与会话）
     - 首页入口：选完先留着，点「开始演易」时随 start() 传到本题会话
     - 工作台入口：题目已经定了，选完当场传

   附件条插在 composer **内部**（textarea 与工具条之间），看起来就是输入框
   自带的一排附件 —— 不改模板结构，也就不碰原型那 162 个函数依赖的 id。
   三种状态各自可见：待上传（黄点，文件还只在浏览器里）、上传中（蓝点闪，
   正在送进会话）、已上传（绿点，智能体读得到）。传完还会 toast 一次。

   注意：这里的内联处理器一律从 data-* 取参数、不写引号（bridge 是模板字符串，
   写 \' 会被提前解义成 ' 而生成出坏代码）。 */

/** 附件条的挂载点：composer 内部、textarea 之后、工具条之前 */
function attachStripEl(scope,create){
  var input=$(scope==='home'?'home-input':'work-input');
  if(!input) return null;
  var box=input.closest('.composer')||input.parentNode;
  var el=document.getElementById('attach-strip-'+scope);
  if(!el&&create&&box){
    el=document.createElement('div');
    el.id='attach-strip-'+scope;
    el.className='attach-strip';
    var bar=box.querySelector('.cbar');
    if(bar) box.insertBefore(el,bar); else box.appendChild(el);
  }
  return el;
}

/** 键盘删除：输入框为空时按退格删最后一个；点中高亮的那条按退格/删除删它 */
function attachBindKeys(scope){
  var input=$(scope==='home'?'home-input':'work-input');
  if(!input||input.__attachKeys) return;
  input.__attachKeys=true;
  input.addEventListener('keydown',function(e){
    if(e.key!=='Backspace'&&e.key!=='Delete') return;
    var H=getAnalysisHooks();
    if(!H||!H.heldList) return;
    var list=H.heldList(scope)||[];
    if(!list.length) return;
    var box=attachStripEl(scope,false);
    var picked=box?box.querySelector('.at-chip.sel'):null;
    if(picked){
      var pb=picked.querySelector('button');
      e.preventDefault();
      attachRemoveKey(scope,pb?pb.getAttribute('data-k'):'');
      return;
    }
    /* 只吃「输入框为空」时的退格 —— 有内容时那是正常的删字 */
    if(e.key==='Backspace'&&!input.value){
      e.preventDefault();
      attachRemoveKey(scope,list[list.length-1].key);
    }
  });
}

/** 附件条的签名：内容或状态一变就得重画，没变就别动 DOM（免得闪、免得打断点击） */
function attachStripSig(list,bad){
  var s='';
  for(var i=0;i<list.length;i++){
    var st=list[i].uploaded?'1':(list[i].uploading?'u':'0');
    s+=list[i].key+'|'+st+';';
  }
  for(var k=0;k<(bad||[]).length;k++) s+='!'+bad[k].key+';';
  return s;
}

/** 点一下 chip 选中（蓝框），再按退格/删除就删它；点第二条时取消前一条 */
function attachChipTap(chip,ev){
  if(ev&&ev.target&&ev.target.tagName==='BUTTON') return;
  if(!chip) return;
  var box=chip.parentNode;
  var on=String(chip.className||'').indexOf(' sel')>=0;
  if(box){
    var all=box.querySelectorAll('.at-chip');
    for(var i=0;i<all.length;i++){
      all[i].className=String(all[i].className).replace(' sel','');
    }
  }
  if(!on) chip.className=chip.className+' sel';
}

/** 画附件条：三种状态各有各的点色与文案，鼠标停上去还说清「什么时候传」 */
function attachStripRender(scope){
  var el=attachStripEl(scope,true);
  if(!el) return;
  attachBindKeys(scope);
  var H=getAnalysisHooks();
  var list=(H&&H.heldList)?(H.heldList(scope)||[]):[];
  var bad=(H&&H.rejectedList)?(H.rejectedList(scope)||[]):[];
  var html='';
  for(var i=0;i<list.length;i++){
    var f=list[i];
    var isProb=f.kind==='problem';
    var cls=f.uploaded?' up':(f.uploading?' ing':'');
    if(isProb) cls=cls+' prob';
    var st=f.uploaded?'已上传':(f.uploading?'上传中…':'待上传');
    var tip;
    if(isProb){
      if(f.uploaded) tip='题目文件已在本会话，智能体按它来理解题目';
      else if(f.uploading) tip='正在上传题目文件…';
      else if(scope==='home') tip='题目文件：点「开始演易」时自动上传，智能体按它来理解题目';
      else tip='题目文件：本题会话还没建立，稍后会自动上传';
    }
    else if(f.uploaded) tip='已保存到本题会话，智能体可以直接读取';
    else if(f.uploading) tip='正在上传到本题会话…';
    else if(scope==='home') tip='点「开始演易」时自动上传到本题会话';
    else tip='本题会话还没建立，稍后会自动上传';
    html+='<span class="at-chip'+cls+'" title="'+esc(tip)+'"><i></i>'+
      (isProb?'<em>题目</em>':'')+
      '<b>'+esc(f.name)+'</b>'+
      '<span class="st">'+st+'</span>'+
      '<button data-k="'+esc(f.key)+'" title="移除（也可按退格键）" onclick="attachStripRemove(this)">×</button></span>';
  }
  /* 没过校验的要留在条上 —— 只弹一句 toast 的话，用户选完看到"没反应"，
     会以为已经交出去了，接着就来问「智能体怎么读不到」 */
  for(var b=0;b<bad.length;b++){
    html+='<span class="at-chip bad" title="'+esc(bad[b].reason+'（这份文件不会被上传；点 × 去掉这条提示）')+'"><i></i>'+
      '<b>'+esc(bad[b].name)+'</b>'+
      '<span class="st">未通过校验</span>'+
      '<button data-k="'+esc(bad[b].key)+'" title="去掉这条提示" onclick="attachStripRemove(this)">×</button></span>';
  }
  el.innerHTML=html;
  el.setAttribute('data-sig',attachStripSig(list,bad));
  /* 点选绑定放在这里 —— innerHTML 每次重画都会换掉节点，事件得跟着重绑 */
  var chips=el.querySelectorAll('.at-chip');
  for(var j=0;j<chips.length;j++){
    (function(c){ c.addEventListener('click',function(ev){ attachChipTap(c,ev); }); })(chips[j]);
  }
}

/* 上传是 flow 内部完成的（start() 里先登记会话再传），core 收不到通知 ——
   用上一次的快照对账：等「待上传归零、且已上传变多」的那一刻提示一次。
   不然用户盯着「待上传」永远猜不到到底是传了还是没传。 */
var attachSeen={};

/** 对账：真的传上去了才吭声，一次只吭一声 */
function attachNoteSync(scope,list){
  var up=0,ing=0,pend=0;
  for(var i=0;i<list.length;i++){
    if(list[i].uploaded) up++;
    else if(list[i].uploading) ing++;
    else pend++;
  }
  var prev=attachSeen[scope];
  attachSeen[scope]={up:up,pend:pend};
  if(!prev) return;
  if(prev.pend>0&&pend===0&&ing===0&&up>prev.up){
    /* 题目文件与数据附件分开说 —— 前者是题面，别把两者混成一句「附件」 */
    var prob=0;
    for(var j=0;j<list.length;j++){ if(list[j].uploaded&&list[j].kind==='problem') prob++; }
    var parts=[];
    if(prob) parts.push('题目文件已上传');
    if(up-prob>0) parts.push((up-prob)+' 个附件已上传');
    toast(parts.join('、')+'，智能体可以直接读取',IC_OK);
  }
}

/**
 * 按需重画。
 *
 * 上传是在分析通道内部完成的（start() 里先登记会话再传），core 这边不会收到
 * 通知 —— 所以由读题气泡那个定时器顺带对一次账：签名变了才重画。
 */
function attachStripSync(scope){
  var H=getAnalysisHooks();
  var list=(H&&H.heldList)?(H.heldList(scope)||[]):[];
  var bad=(H&&H.rejectedList)?(H.rejectedList(scope)||[]):[];
  var el=attachStripEl(scope,false);
  if(!el){ if(list.length||bad.length) attachStripRender(scope); }
  else if(el.getAttribute('data-sig')!==attachStripSig(list,bad)) attachStripRender(scope);
  attachNoteSync(scope,list);
}

/** 按 key 移除一个附件：按钮点、键盘删都走这条 */
function attachRemoveKey(scope,key){
  var H=getAnalysisHooks();
  if(!H||!H.removeHeld||!key) return;
  attachSeen[scope]=null;   /* 主动撤掉的不该触发「已上传」提示 */
  var after=function(){ attachStripRender(scope); };
  var p=H.removeHeld(scope,key);
  if(p&&p.then) p.then(after,after); else after();
}

/** 附件条上的「移除」：没通过的只撤提醒；待上传的直接丢，已上传的连会话里的文件一起删 */
function attachStripRemove(btn){
  var chip=btn;
  while(chip&&String(chip.className||'').indexOf('at-chip')<0){ chip=chip.parentNode; }
  var box=chip&&chip.parentNode;
  var scope=(box&&box.id==='attach-strip-work')?'work':'home';
  var key=btn&&btn.getAttribute?btn.getAttribute('data-k'):'';
  /* 没通过校验的那条只是提醒，撤掉它不该碰真附件 */
  if(chip&&String(chip.className||'').indexOf('bad')>=0){
    var HB=getAnalysisHooks();
    if(HB&&HB.dropRejected&&key){ HB.dropRejected(scope,key); attachStripRender(scope); }
    return;
  }
  attachRemoveKey(scope,key);
}

/** 换了一道题：摘掉上一题已上传的附件，并把附件条重画 */
function attachBeginProblem(){
  var H=getAnalysisHooks();
  if(H&&H.beginProblem) H.beginProblem();
  attachSeen.home=null;
  attachSeen.work=null;
  attachStripRender('home');
  attachStripRender('work');
}

/* ── 题目文件（本轮新增）─────────────────────────────────────────────────

   题面有两个来源：输入框里手写的文字，和用户上传的题目文件。后者让
   「只传文件、一个字不打」也能开跑 —— 题面正文由文件承担，站点只负责
   把它送进会话，并告诉智能体「题面在这个文件里，先读它」。 */

/** 题目文件的显示名（去掉扩展名）—— 只传了题目文件时，用它当工作台标题 */
function attachProblemTitle(scope){
  var H=getAnalysisHooks();
  if(!H||!H.problemTitle) return '';
  return H.problemTitle(scope)||'';
}

/** 这个入口有没有题目文件（决定「开始演易」放不放行、题面从哪来）*/
function attachHasProblem(scope){
  var H=getAnalysisHooks();
  return !!(H&&H.hasProblemFile&&H.hasProblemFile(scope));
}

/** 「上传题目」按钮：文件进题目槽 */
export function pickProblem(scope){ pickFile(scope,'problem'); }

/** 「上传附件」按钮：文件进附件槽 */
export function pickData(scope){ pickFile(scope,'data'); }

/** 选完文件：校验 + 暂存；工作台里再当场传到本题会话。
 *  kind 决定这份文件算题面（'problem'）还是补充材料（'data'）—— 两边的
 *  提示文案与附件条样式都据此分开，用户点哪个按钮就该得到哪套反馈。 */
function attachPick(scope,files,names,kind){
  var H=getAnalysisHooks();
  if(!H||!H.holdFiles){ toast('当前环境没有接入附件通道，文件不会被上传'); return; }
  kind=kind||'data';
  var r=H.holdFiles(scope,files,kind)||{accepted:0,rejected:[]};
  var bad=r.rejected||[];
  var what=kind==='problem'?'题目文件':'附件';
  var msg='已选中 '+files.length+' 个'+what+'：'+names.join('、');
  if(bad.length) msg=msg+'；有 '+bad.length+' 个没收下（'+bad.join('；')+'）——没收下的不会被上传，已在附件条上标出。';
  if(!r.accepted) msg=msg+'没有一个能上传。';
  else if(kind==='problem'&&scope==='home') msg=msg+'。现在就可以点「开始演易」了。';
  else if(kind==='problem') msg=msg+'。开题后智能体按它理解题目。';
  else if(scope==='home') msg=msg+'。点「开始演易」时自动上传。';
  toast(msg,bad.length?IC_WARN:IC_OK);
  attachStripRender(scope);
  if(scope!=='work'||!r.accepted||!H.attachNow) return;
  attachStripSync('work');   /* 先亮出「上传中」—— 不然这一段是看不见的 */
  H.attachNow('work').then(function(failed){
    attachStripSync('work');
    if(failed&&failed.length) toast(failed.join('；'),IC_WARN);
    attachLateNotice();
  },function(){ attachStripSync('work'); });
}

/** 有没有「传上去了、但这一轮读不到」的附件 */
function attachLateList(){
  var H=getAnalysisHooks();
  return (H&&H.lateAttachments)?(H.lateAttachments()||[]):[];
}

/**
 * 刚传的附件没赶上这一轮的实话 + 重跑入口。
 *
 * 这一轮 run 的提示词在进工作台时就发出去了，里面没有这份附件 —— 智能体
 * 那一轮确实读不到。只显示一个绿色的「已上传」会让用户以为它已经看到，
 * 所以这里把话说清楚，并给一个动作把它补进去。
 */
function attachLateNotice(){
  var H=getAnalysisHooks();
  var late=attachLateList();
  if(!late.length) return;
  var names=[]; for(var i=0;i<late.length;i++) names.push(late[i].name);
  var running=!!(H&&H.status&&H.status()==='running');
  pushMsg({who:'ai',html:'<h4>这份附件这一轮读不到</h4>'+
    '<p>「'+esc(names.join('、'))+'」已经传到本题会话工作区，但这一轮的题目与分析请求<b>在它上传之前就发出去了</b>，智能体手上没有它的清单。</p>'+
    '<p class="src-note">'+(running
      ? '这一轮还在跑 —— 等它结束，再点下面的按钮把它带上重跑一次。'
      : '点下面的按钮，带上它重新分析一次。')+'</p>'+
    '<div class="run-acts"><button class="abtn attach-rerun" onclick="attachRerun(this)">带上刚上传的附件重新分析</button></div>'},mkey());
  scrollBottom();
}

/** 「带上刚上传的附件重新分析」：丢结果、留附件，重新发起一次 */
function attachRerun(btn){
  var H=getAnalysisHooks();
  if(!H||!H.rerun){ toast('当前环境没有接入分析通道',IC_WARN); return; }
  try { H.rerun(); }
  catch(e){ toast(e&&e.message?e.message:'现在还不能重跑',IC_WARN); return; }
  if(btn){ btn.disabled=true; btn.textContent='已重新发起…'; }
  toast('重新分析已开始，这次会带上刚上传的附件',IC_OK);
  /* ~接入改动（2026-10-13）：动画通道不再在这里重新叫起 —— 它从开题起就在后台
     跑着，右栏「演示」面板有自己的轮询；重跑想刷新演示用面板里的「重试」。 */
  analysisBoot(state.workTitle,!!analysisFromDetail,function(){});
}

/* ── S2 真实动画通道（非原型原码，本轮新增）───────────────────────────────

   把「分析 → 可视化演示」从**内置演示**换成分析动画智能体实时生成：
   在后台生成（不阻塞当前步骤），右下角小浮窗报进度；生成完的每一档
   登记进 ANIM_BANK，内容用 blob URL 挂 iframe（与全屏层同一隔离口径）。

   诚实性三条：
     - 接管期间**不登记内置演示**（regAnims 改走 animRealRegister），
       免得真实与内置混在一张列表里分不清；
     - 失败不说成成功：抽屉里如实写「这一档没有真实产物」；
     - 换题 / 重试把上一题留下的 blob URL 全部 revoke。 */

var ANIM_REAL_ON=false;         /* 真实动画是否接管（接管期间内置演示让位） */
var ANIM_REAL_TRIED=false;      /* 这道题是否试过真实通道（区分「没试过」与「试过但失败」） */
var ANIM_REAL_URLS={};          /* k → blob URL */
/* ⛔ 2026-10-15 浮窗移除后，只留这条轮询句柄（done/failed 的登记副作用用） */
var ANIM_POLL_TIMER=null;

/** 真实动画是否已接管 */
function animRealActive(){ return ANIM_REAL_ON; }

/** 已就绪的真实档位；未就绪返回空数组。
 *  ~后台改动：running 期间也返回**已收到**的档位 —— 右栏「出到哪档看到哪档」，
 *  不用等整条流水线收工（完成判据在通道里，是验收产物落盘）。 */
function animRealItems(){
  var A=getAnimationHooks();
  if(!A) return [];
  var st=A.status();
  if(st==='done') return A.takeAnims()||[];
  if(st==='running'&&A.knownAnims) return A.knownAnims()||[];
  return [];
}

/** 按站点键找一档 */
function animRealItem(k){
  var list=animRealItems();
  for(var i=0;i<list.length;i++) if(String(list[i].k)===String(k)) return list[i];
  return null;
}

function animRealTitle(k){ var it=animRealItem(k); return it?it.title:''; }

/** 这一档的 blob URL（懒创建；没有这一档返回空串） */
function animRealURL(k){
  var it=animRealItem(k);
  if(!it||!it.html) return '';
  if(!ANIM_REAL_URLS[k]){
    try{ ANIM_REAL_URLS[k]=URL.createObjectURL(new Blob([it.html],{type:'text/html'})); }
    catch(e){ return ''; }
  }
  return ANIM_REAL_URLS[k];
}

/** 撤掉所有 blob URL（换题 / 重试 / 重新开始） */
function animRealRevoke(){
  for(var k in ANIM_REAL_URLS){
    if(!Object.prototype.hasOwnProperty.call(ANIM_REAL_URLS,k)) continue;
    try{ URL.revokeObjectURL(ANIM_REAL_URLS[k]); }catch(e){}
  }
  ANIM_REAL_URLS={};
}

/** 把真实档位登记进 ANIM_BANK（regAnims 在接管期间改走这里） */
function animRealRegister(){
  var list=animRealItems();
  for(var i=0;i<list.length;i++){
    var it=list[i], hit=null;
    for(var j=0;j<ANIM_BANK.length;j++) if(String(ANIM_BANK[j].k)===String(it.k)) hit=ANIM_BANK[j];
    if(hit){ hit.title=it.title; hit.sub=it.sub; }
    else ANIM_BANK.push({k:String(it.k),title:it.title,sub:it.sub});
  }
  refreshAnimDrawer();     /* 抽屉开着就立刻换成真实内容 */
}

/** 舞台里的真实动画：iframe 挂 blob URL（与全屏层同一隔离口径） */
function animRealFrameHTML(k){
  var url=animRealURL(k);
  if(!url) return '';
  return '<iframe class="anim-frame" data-anim="'+esc(String(k))+'" title="'+esc(animRealTitle(k)||'分析动画')+'" src="'+esc(url)+'"></iframe>';
}

/** 真实列表里那排标签的事件：渲染后才有，用 data-wired 防重复绑定 */
function wireAnimTabs(){
  var box=$('drawer-body');
  if(!box) return;
  var all=box.querySelectorAll('[data-dq]');
  for(var i=0;i<all.length;i++){
    var b=all[i];
    if(b.getAttribute('data-wired')) continue;
    b.setAttribute('data-wired','1');
    b.addEventListener('click',(function(k){
      return function(){ drawerAnim(k); };
    })(b.getAttribute('data-dq')));
  }
}

/**
 * 动画抽屉的正文。
 *
 * 返回空串表示「真实通道没试过」→ 调用方（artBody）走内置那份列表。
 * 试过就一律由这里作答：生成中 / 没生成出来 / 已就绪三种口径分明，
 * 绝不拿内置演示冒充真实结果。
 */
function animRealDrawerHTML(){
  if(!ANIM_REAL_TRIED) return '';
  var A=getAnimationHooks();
  var head='<div class="card-h">'+ART.animation.icon.replace('#fff','#17A2C4')+'<h3>可视化演示</h3>';
  var list=animRealItems();
  if(!list.length){
    var st=A?A.status():'idle';
    var running=st==='running';
    var tip=running?'正在生成分析动画…':'这一档没有拿到真实动画';
    var why=(!running&&A&&A.failureText)?(A.failureText()||''):'';
    return '<div class="card">'+head+'<span class="tag-c" style="font-size:11px">'+(running?'生成中':'未生成')+'</span></div>'+
      '<div class="card-b">'+
        '<div class="stage dim"><div class="stage-empty">'+esc(tip)+'<span>动画在后台生成，完成一档就出现在这里</span></div></div>'+
        '<p class="src-note">这里的动画由分析动画智能体按本题题面生成，不是平台内置演示。'+(why?('这次没拿到：'+esc(why)+'。'):'')+'</p>'+
      '</div></div>';
  }
  var cur=String(ANIM_CUR), has=false;
  for(var i=0;i<list.length;i++) if(String(list[i].k)===cur) has=true;
  if(!has) cur=String(list[0].k);
  ANIM_CUR=cur;
  var cards=list.map(function(it){
    var k=String(it.k);
    return '<button class="acard'+(k===cur?' open':'')+'" id="dq-'+esc(k)+'" data-dq="'+esc(k)+'" style="margin-bottom:8px">'+
      '<span class="ai" style="background:linear-gradient(120deg,#12A594,#1E9FC4)">'+esc(k)+'</span>'+
      '<span class="at"><b>'+esc(it.title)+'</b><span>'+esc(it.sub)+'</span></span></button>';
  }).join('');
  return '<div class="card">'+head+'<span class="tag-c" style="font-size:11px">智能体生成 · '+list.length+' 档</span></div>'+
    '<div class="card-b">'+
      '<div class="stage" id="d-stage">'+fsBtnHTML(cur)+animRealFrameHTML(cur)+'</div>'+
      '<p class="src-note">这些动画由分析动画智能体按本题题面生成（与上面那份分析同源），不是平台内置演示。</p>'+
      '<div style="margin-top:14px">'+cards+'</div>'+
    '</div></div>';
}

/* ⛔ 进度浮窗已移除（2026-10-15，按用户要求）
   ─────────────────────────────────────────────────────────────
   原来右下角有一个常驻浮窗（「正在生成分析动画 / 已用时 X 秒 · 已出 N 档 /
   动画在后台生成… / 先收起」，含一块思考·工具明细实时区）。现已整体去掉：
   右栏「可视化演示」tab 是唯一的进度出口，浮窗与之重复。

   连带影响（有意为之）：浮窗那块的 `.anf-live` 是动画链路的实时区通道宿主，
   不建浮窗 ⇒ 不再 liveAttach ⇒ 流式思考/工具明细不再上屏（本来也只画在浮窗里）。
   产物流转、右栏「出到哪档看到哪档」全部照旧。

   下面的状态轮询**必须保留**：它不是为浮窗服务的，还承担两个副作用 ——
   done 时把已收到的档位登记给右栏（animRealRegister）、failed 时刷新抽屉。 */

/** 动画状态轮询（原 animFloatWatch，已去掉一切浮窗 UI） */
function animStatusWatch(){
  var A=getAnimationHooks();
  if(!A) return;
  if(ANIM_POLL_TIMER) clearInterval(ANIM_POLL_TIMER);
  ANIM_POLL_TIMER=setInterval(function(){
    var st=A.status();
    if(st==='done'){
      clearInterval(ANIM_POLL_TIMER); ANIM_POLL_TIMER=null;
      ANIM_REAL_ON=true;
      animRealRegister();
    }else if(st==='failed'){
      clearInterval(ANIM_POLL_TIMER); ANIM_POLL_TIMER=null;
      ANIM_REAL_ON=false;
      refreshAnimDrawer();
    }
  },900);
}

/* ── 右栏「演示」面板（2026-10-13 新增）────────────────────────────────────

   动画在后台生成：解答做到哪一步，右栏就出哪一步的演示。面板沿用原型
   「可视化演示」那套样式（.card + .stage + 档位 chips），常驻右栏第三个 tab；
   点档位即切，全屏按钮照旧进 .afs 全屏层。

   「跟随解答步骤」用 curStep（renderSteps / syncSteps 写入，0=全局分析、
   1..4=问题一～四）—— 与动画档位的 k 是同一套序号（动画助手的产物文件名
   序号前缀就是步骤号）。自动跟随的取法：优先与解答当前步骤同号的档；
   动画还没跑到解答进度就退回不超过它的最大号；先行出了后面的档也不留空。

   驱动：onUpdate（档位新增）+ 1.2 秒轮询（状态 / 进度 / 解答步骤变化）。
   重画前先对签名 —— stage 里的 iframe 一重画就重播，能省则省。 */

var ANIM_RAIL_TIMER=null;      /* 面板轮询句柄 */
var ANIM_RAIL_SIG='';          /* 上次渲染签名 */
var ANIM_RAIL_CUR=null;        /* 用户手动选中的档位；null=自动跟随解答步骤 */
var ANIM_RAIL_UN=null;         /* onUpdate 订阅的退订函数 */

/** 右栏演示面板的容器；不在工作台视图 / 右栏不在时返回 null。
 *  画进 pane 里的 .rail-anim-body（shell 里预留的骨架），不整个替换 pane。 */
function animRailHost(){
  if(typeof state==='undefined'||!state||state.view!=='work') return null;
  var pane=$('rail-pane-anim'); if(!pane) return null;
  return pane.querySelector('.rail-anim-body')||pane;
}

function animRailBadge(n){
  var b=$('rail-anim-badge');
  if(b){ b.textContent=n?String(n):''; b.style.display=n?'':'none'; }
}

/** 自动跟随该看哪一档（见上方注释）；列表为空返回 null */
function animRailFollow(list){
  if(!list.length) return null;
  var cur=Number(curStep)||0, floor=null, min=null;
  for(var i=0;i<list.length;i++){
    var k=Number(list[i].k);
    if(k===cur) return list[i];
    if(k<cur&&(!floor||k>Number(floor.k))) floor=list[i];
    if(!min||k<Number(min.k)) min=list[i];
  }
  return floor||min;
}

/** 当前该看哪一档：手动选中的还在列表里就尊重手动，否则自动跟随 */
function animRailPick(list){
  if(ANIM_RAIL_CUR!==null){
    for(var i=0;i<list.length;i++) if(String(list[i].k)===String(ANIM_RAIL_CUR)) return list[i];
  }
  return animRailFollow(list);
}

/** 面板结构签名：档位集合 / 选中项 / 解答步骤 / 状态（失败时连原因一起）。
 *  ⚠️ 故意不含 progressText —— 里面有「已用时 N 秒」，每秒都变，进签名会让
 *  stage 里的 iframe 每 1.2 秒重建一次（动画反复重播）。进度只更新说明行。 */
function animRailSig(){
  var A=getAnimationHooks();
  var bits=[A?A.status():'idle', animRealItems().map(function(x){return x.k;}).join(','),
    ANIM_RAIL_CUR===null?'':String(ANIM_RAIL_CUR), String(curStep||0)];
  if(A&&A.status()==='failed'&&A.failureText) bits.push(A.failureText());
  return bits.join('|');
}

/** 说明行文案（running 的进度文案由调用方带 noteSig 增量刷新） */
function animRailNoteHTML(st,hasIt,A){
  if(st==='failed'){
    return '这次没拿到：'+esc((A&&A.failureText)?(A.failureText()||'动画通道没有返回结果'):'')+'。不影响解答继续走。';
  }
  if(st==='running'){
    return '正在按本题题面生成分析动画'+((A&&A.progressText)?'：'+esc(A.progressText()):'…');
  }
  if(hasIt) return '由本会话的分析动画智能体按本题题面生成（全局 + 每问一档），不是平台内置演示。';
  return '走到「全局分析」后，本会话会按本题题面逐档生成演示。';
}

/** 档位 chips：STEPS 顺序；已出的可点切换，没出的置灰「待出」（与全屏 tab 同口径） */
function animRailChipsHTML(list){
  var picked=animRailPick(list);
  var cur=picked?String(picked.k):null;
  var have={};
  for(var i=0;i<list.length;i++) have[String(list[i].k)]=list[i];
  var maxK=-1;
  for(var key in have){ var n=Number(key); if(n>maxK) maxK=n; }
  var total=Math.max(STEPS.length-1,maxK);
  var out=[];
  for(var s=0;s<=total;s++){
    var it=have[String(s)];
    var label=STEPS[s]||('第 '+(s+1)+' 档');
    if(it){
      out.push('<button type="button" class="rchip'+(cur===String(s)?' on':'')+'" data-animk="'+s+'" title="'+esc(it.title||label)+'">'+esc(label)+'</button>');
    }else{
      out.push('<button type="button" class="rchip lock" title="这一档还没生成出来">'+esc(label)+'<span class="lk"> · 待出</span></button>');
    }
  }
  return '<div class="rchips">'+out.join('')+'</div>';
}

function animRailRender(){
  var host=animRailHost(); if(!host) return;
  var A=getAnimationHooks();
  var st=A?A.status():'idle';
  var list=animRealItems();
  var sig=animRailSig();
  var noteSig=(A&&A.progressText)?A.progressText():'';
  var pane=$('rail-pane-anim');
  var on=!!(pane&&pane.classList.contains('on'));
  /* 徽标：演示 tab 不在前台时亮出已到的档数；在前台就清掉 */
  animRailBadge(on?0:list.length);
  if(sig===ANIM_RAIL_SIG&&host.getAttribute('data-built')==='1'){
    /* 结构没变、只有进度文案变了（已用时 / 工具次数在跳）：只换说明行 ——
       stage 里的 iframe 一重建就重播，不能为了刷新一行字把它拆了 */
    var noteEl=host.querySelector('#rail-anim-note');
    if(noteEl&&noteEl.getAttribute('data-note')!==noteSig){
      noteEl.setAttribute('data-note',noteSig);
      noteEl.innerHTML=animRailNoteHTML(st,!!animRailPick(list),A);
    }
    return;
  }
  ANIM_RAIL_SIG=sig;
  host.setAttribute('data-built','1');
  /* 有真变化才登记 —— 登记会重画动画抽屉（iframe 重载），别让 1.2s 轮询白折腾 */
  if(list.length) animRealRegister();    /* 全屏 / 抽屉与右栏共用同一份登记 */

  var it=animRailPick(list);
  var stage;
  if(it){
    stage='<div class="stage" id="rail-anim-stage">'+fsBtnHTML(String(it.k))+animRealFrameHTML(String(it.k))+'</div>';
  }else{
    var tip=st==='running'?'分析动画正在准备…':(st==='failed'?'这一题没有拿到可视化演示':'解答开跑后，演示会在这里逐档出现');
    stage='<div class="stage dim"><div class="stage-empty">'+esc(tip)+
      '<span>全局分析最先出，之后每问一档；解答做到哪一步，右边就出哪一步的</span></div></div>';
  }
  var retry=st==='failed'
    ?'<div class="run-acts"><button class="abtn gh" id="rail-anim-retry">重试生成演示</button></div>'
    :'';
  host.innerHTML='<div class="card">'+
    '<div class="card-h">'+ART.animation.icon.replace('#fff','#17A2C4')+'<h3>可视化演示</h3>'+
      '<span class="tag-c" style="font-size:11px">'+(st==='running'?'生成中':(st==='failed'?'未生成':(it?('已就绪 · '+list.length+' 档'):'待启动')))+'</span>'+
    '</div>'+
    '<div class="card-b">'+stage+
      '<p class="src-note" id="rail-anim-note" data-note="'+esc(noteSig)+'">'+animRailNoteHTML(st,!!it,A)+'</p>'+
      (list.length?animRailChipsHTML(list):'')+retry+
    '</div></div>';

  /* 档位 chips 与重试按钮：渲染后才有，用监听补（原型 inline onclick 只认 window 上的名字） */
  var chips=host.querySelectorAll('[data-animk]');
  for(var i=0;i<chips.length;i++){
    (function(b){
      b.addEventListener('click',function(){
        ANIM_RAIL_CUR=b.getAttribute('data-animk');
        animRailRender();
      });
    })(chips[i]);
  }
  var r=host.querySelector('#rail-anim-retry');
  if(r) r.addEventListener('click',function(){ ANIM_RAIL_CUR=null; animationBoot(); });
}

function animRailStart(){
  var A=getAnimationHooks();
  if(!A) return;
  ANIM_RAIL_CUR=null;
  ANIM_RAIL_SIG='';
  var host=animRailHost();
  if(host) host.removeAttribute('data-built');
  if(ANIM_RAIL_UN){ try{ ANIM_RAIL_UN(); }catch(e){} ANIM_RAIL_UN=null; }
  if(A.onUpdate) ANIM_RAIL_UN=A.onUpdate(function(){ animRailRender(); });
  if(!ANIM_RAIL_TIMER){
    ANIM_RAIL_TIMER=setInterval(function(){ animRailRender(); },1200);
  }
  animRailRender();
}

/** 新题 / 重试：面板回到空态（轮询与订阅保留 —— 下一轮 animationBoot 会重新接上） */
function animRailReset(){
  ANIM_RAIL_CUR=null;
  ANIM_RAIL_SIG='';
  var pane=$('rail-pane-anim');
  if(pane){
    var body=pane.querySelector('.rail-anim-body')||pane;
    body.removeAttribute('data-built');
    body.innerHTML='';
  }
  animRailBadge(0);
}

/**
 * 解答开跑时由桥接层**在后台**发起（不等 S1 —— 2026-10-13 定版）。
 *
 * 对接的是本会话的分析动画智能体（能力在 skills/mm-2-*）：它自带完整动画流水线，题面直接从站点
 * 请求里拿，与解答共用同一条会话。对话不等它；它每落一档动画，右栏
 * 「演示」面板就多一张（解答做到哪一步，右边就出哪一步的）。
 *
 * reuse 先行：刷新后动画会话里已有产物就直接接回，读到了不重跑。
 */
export function animationBoot(){
  var A=getAnimationHooks();
  if(!A) return;
  var ref=analysisRef(state.workTitle,analysisFromDetail);
  if(!ref) return;
  ANIM_REAL_TRIED=true;
  ANIM_REAL_ON=true;          /* 接管期间内置演示不登记 */
  animRealRevoke();           /* 上一题留下的 blob URL 全部撤掉 */
  animRailStart();
  A.reuse(ref).then(function(ok){
    if(ok){ ANIM_REAL_ON=true; animRealRegister(); animRailRender(); }
    else A.start(ref);
  }).catch(function(){ A.start(ref); });
  animStatusWatch();
}

/* ── S3 真实代码通道（非原型原码，本轮新增）───────────────────────────────

   把「选定模型 → 代码」从**内置剧本代码**换成解题代码智能体实时生成：
   选定后弹等待气泡（可跳过），完成后真实源码包交给抽屉展示。

   与 S2 的关键差别：智能体的契约是**只生成不运行**（executed_problem_code:false）。
   所以页内那个「▶ 运行代码」不给 —— 它打的是内置剧本的输出，挂在真实源码下面
   等于把示意值说成实算结果。只留「复制 / 下载 / 交给沙箱」。 */

/** 这道题该用哪份题面（与 S2 同一口径） */
function codegenRef(){ return analysisRef(state.workTitle,analysisFromDetail); }

/** 真实代码通道这次是否可用：S1 跑通 + 有题面 + 钩子已注册 */
function codegenReady(){
  var C=getCodegenHooks(), S=getAnalysisHooks();
  if(!C||!S||S.status()!=='done') return false;
  return !!codegenRef();
}

/** 这一问的真实代码包；没有返回 null */
function codeRealOf(qn){
  var C=getCodegenHooks();
  if(!C) return null;
  try{ return C.takeQuestion(qn); }catch(e){ return null; }
}

/** 选定模型后的回复（真实模式：不照搬内置剧本那段话术） */
function codegenReplyNode(o,qi){
  return '<h4>已采用方案 · '+esc(o.name)+'</h4>'+
    '<p>我按这个方案给<b>问题'+esc(analysisCnAt(qi))+'</b>写一份完整源码包：主程序 + 运行说明 + 依赖清单。</p>'+
    '<p class="next">下一步：代码回来之后设定参数，把源码交给沙箱或本地环境去跑。</p>';
}

/** 真实模式下选完模型的快捷语提示 */
function codegenRunHint(){ return '代码生成中，稍等；也可以直接回一句「继续」'; }

/** 代码生成的等待气泡：进度 + 跳出口 + 「只生成不运行」的实话（进度行不带秒数） */
function codeWaitHTML(qi){
  var cn=analysisCnAt(qi);
  return '<h4>正在为问题'+esc(cn)+'生成代码</h4>'+
    '<p>解题代码智能体按你刚选的模型在写这一问的完整源码包（主程序 + 运行说明 + 依赖清单），所以会久一些。</p>'+
    '<p class="src-note">这条通道只生成代码、不在页面里运行 —— 拿到之后可以复制、下载，或交给沙箱去跑。</p>'+
    '<p class="an-meter"></p>'+
    '<div class="run-acts"><button class="abtn gh code-skip">不等了，先跳过</button></div>';
}

/** 代码等待气泡进度行这一刻的文字 */
function codeMeterText(qi){
  var C=getCodegenHooks();
  var st=C?C.questionStatus(qi):'idle';
  if(st!=='running') return '正在准备工作区…';
  return (C&&C.questionProgressText&&C.questionProgressText(qi))||'正在准备工作区…';
}

/** 等待气泡状态区：模具等值守卫 + 秒数走 textContent */
function syncCodeWait(el,qi){
  if(!el) return;
  var box=el.querySelector('.txt');
  if(!box) return;
  var mold=codeWaitHTML(qi);
  if(box.getAttribute('data-mold')!==mold){
    box.innerHTML=mold;
    box.setAttribute('data-mold',mold);
  }
  paintText(box,'.an-meter',codeMeterText(qi));
}

/**
 * 选完模型后发起这一问的代码生成，并在对话里等到有结果（或用户跳过）。
 * 完成后回调 cb —— 由 cb 决定接下来怎么走（设定参数 / 打开抽屉）。
 */
export function codeBoot(qi,model,cb){
  cb=cb||function(){};
  var C=getCodegenHooks();
  var ref=codegenRef();
  if(!C||!ref){ cb(); return; }

  var el=document.createElement('div');
  el.className='msg ai';
  /* 实时区是 .txt 的**兄弟**，不是它的孩子：状态文案（codeWaitHTML）每 700ms
     整块重写一次，实时区不能跟着被换掉 —— 事件流状态挂在节点上（live 模块的
     WeakMap），节点一换这轮就只剩最后一次重画之后的明细了。 */
  el.innerHTML='<div class="bubble"><div class="txt">'+codeWaitHTML(qi)+'</div>'+
    '<div class="lv-stream code-live"></div></div>';
  workHost().appendChild(el);
  scrollBottom();
  syncCodeWait(el,qi);   /* 进度行首帧：秒数走 textContent，且**不会**碰实时区 */

  var liveBox=el.querySelector('.code-live');
  /* 先把实时区交出去再发起：startQuestion 内部会 begin 这个通道，晚交就接不到开头那批事件 */
  if(C.liveAttach) C.liveAttach(qi,liveBox);

  var done=false,timer=null;
  function finish(){
    if(done) return; done=true;
    if(timer) clearInterval(timer);
    if(C.liveAttach) C.liveAttach(qi,null);   /* 气泡摘掉，通道一并注销 */
    el.remove();
    cb();
  }
  /* 事件委托：状态区重写不影响监听器 */
  el.addEventListener('click',function(ev){
    var t=ev.target;
    if(t&&t.closest&&t.closest('.code-skip')) C.skipQuestion(qi);
  });

  /* 刷新后产物可能已经在会话里 —— 先试读；读到就不烧模型调用 */
  C.reuseQuestion(ref,qi,model).then(function(ok){
    if(done) return;
    if(!ok) C.startQuestion(ref,qi,model);
  }).catch(function(){ if(!done) C.startQuestion(ref,qi,model); });

  timer=setInterval(function(){
    var st=C.questionStatus(qi);
    if(!done) syncCodeWait(el,qi);
    /* 实时区：不参与上面的重写，只按 live 模块那套刷新。
       可见性交给 CSS 的 :empty —— 不在这里切 display，避免「有内容却被藏着」的空窗。 */
    if(liveBox&&C.liveRender) C.liveRender(liveBox);
    scrollBottom();
    if(st==='done'||st==='failed'){
      if(st==='failed'){
        /* 内置剧本已删（2026-10-08）：拿不到代码就如实说，不拿写死内容顶 */
        var why=C.questionFailureText(qi)||'';
        pushMsg({who:'ai',html:'<h4>问题'+esc(analysisCnAt(qi))+'这次没拿到代码</h4>'+
          '<p class="src-note">这一问的源码由解题代码智能体生成，本次没拿到；你可以在下方输入框里追问，或稍后重试。</p>'+
          (why?'<p class="src-note">原因：'+esc(why)+'</p>':'')},mkey());
      }else{
        pushMsg({who:'ai',html:'<h4>问题'+esc(analysisCnAt(qi))+'的代码已生成</h4>'+
          '<p>源码包来自解题代码智能体（按 <b>'+esc(model.name||'')+'</b> 生成的这一问），右侧「代码」抽屉里可以看全文、复制、下载。它<b>只生成不运行</b>。</p>'+
          '<p class="next">下一步：把源码交给沙箱或本地环境去跑。</p>'},mkey());
      }
      finish();
    }
  },700);
}

/** 把真实源码灌进这一问（代码检测 import 时就跟着走真实值） */
function codeApplyToQuiz(qi){
  var pkg=codeRealOf(qi);
  var q=QUIZ[qi];
  if(pkg&&pkg.mainText&&q) q._codeText=pkg.mainText;
}

/** 代码那一步的来源说明：真实 / 内置分开写 */
function codeLabNote(){
  if(codeRealOf(qi)) return '<p class="src-note">这一问的源码由解题代码智能体按本题题面生成，<b>只生成不运行</b>；要跑请交给沙箱或本地环境。</p>';
  return analysisCodeNote();
}

/** 抽屉里的代码正文：真实源码 + 配套文件清单 */
function codeRealBody(qn){
  var pkg=codeRealOf(qn);
  if(!pkg||!pkg.mainText) return '';
  var files=pkg.files||[];
  var list=files.length
    ? '<ul class="sb-files">'+files.map(function(f){
        return '<li><code>'+esc(f.path)+'</code><span>'+String((f.content||'').length)+' 字</span></li>';
      }).join('')+'</ul>'
    : '<p class="side-note">这个包只有主程序一个文件。</p>';
  var lines=String(pkg.mainText).split('\n').length;
  return '<div class="card"><div class="card-h">'+ART.code.icon.replace('#fff','#1E9FC4')+'<h3>'+esc(pkg.mainName||'main.py')+'</h3><div class="grow"></div>'+
      '<button class="btn-ghost" data-act="copy">复制</button>'+
      '<button class="btn-ghost" data-act="download">下载</button></div>'+
    '<p class="src-note">这份源码由解题代码智能体按本题题面生成（模型：'+esc(pkg.model||'')+'），<b>只生成不运行</b> —— 页内不给模拟运行，要跑请交给沙箱或本地环境。</p>'+
    '<div class="card-b" style="padding:0"><div class="code-box">'+
      '<div class="code-top"><span class="f">'+esc(pkg.mainName||'main.py')+' · Python · '+lines+' 行</span></div>'+
      '<pre class="code">'+hl(pkg.mainText)+'</pre>'+
    '</div></div></div>'+
    '<div class="card"><div class="card-h"><h3>配套文件</h3></div><div class="card-b">'+list+'</div></div>';
}

/** 抽屉渲染完成后补绑：复制 / 下载（这些节点渲染后才有，绑不了内联处理器） */
export function wireRealCodeActions(){
  var box=$('drawer-body');
  if(!box) return;
  var c=box.querySelector('[data-act="copy"]');
  if(c) c.addEventListener('click',function(){ codeCopyText(); });
  var d=box.querySelector('[data-act="download"]');
  if(d) d.addEventListener('click',function(){ codeDownloadText(); });
}

function codeCopyText(){
  var pkg=codeRealOf(qi);
  if(!pkg) return;
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(pkg.mainText||'').then(
      function(){ toast('源码已复制'); },
      function(){ toast('复制失败，请手动选中源码'); });
  }else toast('这个浏览器不支持一键复制，请手动选中源码');
}

function codeDownloadText(){
  var pkg=codeRealOf(qi);
  if(!pkg) return;
  try{
    var a=document.createElement('a');
    a.href=URL.createObjectURL(new Blob([pkg.mainText||''],{type:'text/plain;charset=utf-8'}));
    a.download=pkg.mainName||'main.py';
    document.body.appendChild(a); a.click();
    document.body.removeChild(a);
    var url=a.href;
    setTimeout(function(){ try{ URL.revokeObjectURL(url); }catch(e){} },2000);
    toast('已下载 '+(pkg.mainName||'main.py'));
  }catch(e){ toast('下载失败，请改用复制'); }
}

/** 重新开始一道题：把动画 / 代码两条通道里属于上一题的东西清掉 */
export function realChannelsReset(){
  animRealRevoke();
  ANIM_REAL_ON=false;
  ANIM_REAL_TRIED=false;
  if(ANIM_POLL_TIMER){ clearInterval(ANIM_POLL_TIMER); ANIM_POLL_TIMER=null; }
  animRailReset();    /* ~接入改动（2026-10-13）：右栏「演示」面板跟着回到空态 */
  var C=getCodegenHooks(); if(C&&C.reset) C.reset();
  var A=getAnimationHooks(); if(A&&A.reset) A.reset();
}

/* ==================================================================
   工作台任务仓（2026-10-08 新增）

   为什么：工作台的状态（state.workTitle / detailId / 对话区 DOM）以前只在内存里，
   一次刷新、一次 Vite 热更新重载就全没了 —— 重载后 applyRoute 会调 go('work')，
   而 go() 第一行是 if(!state.workTitle){ toast(...); return }，于是视图停在首页、
   地址栏却还挂着 #/work，看着就是「聊着聊着跳回首页」。

   这里做两件事：
     ① 开题时向任务仓登记一条（题面 / 题号 / 是否来自详情页）；
     ② 对话区**按任务分桶** —— 每个任务一个容器，前台那个挂在 #stream-in 里，
        其余任务的容器留在内存（节点不销毁）。所以切走再切回来，两边内容都在，
        也不会出现「A 的异步回调把消息写进 B 的对话」。

   数据落盘在 stores/tasks.ts（Vue 侧）；这里只通过 hooks 问两个问题：
   「现在前台是哪条任务」「开一条新任务」。core 不认识 localStorage。
   ================================================================== */
var WORK_HOSTS={};      /* taskId -> 该任务的对话区容器 */
var WORK_HOST_ID='';    /* 当前挂在 #stream-in 里的那条任务的 id */

function workKey(){
  var W=getWorkHooks();
  return (W&&W.currentId)?(W.currentId()||''):'';
}

/** 把某条任务的容器切到前台（换台） */
function workHostShow(key){
  var slot=$('stream-in'); if(!slot||!key) return null;
  var el=WORK_HOSTS[key];
  if(!el){
    el=document.createElement('div');
    el.className='stream-in';              /* 宽度约束沿用原型那条 .stream-in */
    el.setAttribute('data-work-task',key);
    WORK_HOSTS[key]=el;
  }
  if(el.parentNode!==slot){
    /* 清 slot 只是把「上一条任务」的容器摘下来，节点与它的子树都还在 WORK_HOSTS 里，
       被摘下的那条任务继续往自己的 DOM 写也写得到，切回来原样可见 */
    slot.innerHTML='';
    slot.appendChild(el);
  }
  WORK_HOST_ID=key;
  return el;
}

/**
 * 当前任务的对话区容器；任务仓还没起来时退回 #stream-in（单容器老行为，
 * 单独跑原型逻辑 / 脚本不受影响）。core 里所有「往对话区追加」的调用点都走这里。
 */
function workHost(){
  var slot=$('stream-in');
  if(!slot) return null;
  var key=workKey();
  if(!key) return slot;
  var el=WORK_HOSTS[key];
  if(el&&el.parentNode===slot) return el;
  return workHostShow(key);
}

/** 给页面外壳用：当前任务的对话区容器（沙箱快照 / 离开前快照要读写的那个） */
function workHostEl(){ var el=workHost(); return el||$('stream-in'); }

/** 任务被移除时把它的容器也丢掉，别把 DOM 攒在内存里 */
function workSessionDrop(key){
  if(!key) return;
  var el=WORK_HOSTS[key];
  if(el&&el.parentNode) el.parentNode.removeChild(el);
  delete WORK_HOSTS[key];
  if(WORK_HOST_ID===key) WORK_HOST_ID='';
}

/** 开题：登记一条任务（由 startWork 调用） */
function workSessionBegin(fromDetail){
  var W=getWorkHooks(); if(!W) return;
  /* 题目文件名只在开题这一刻收（那时暂存区一定是对的）：收进任务仓持久化，
     刷新后 workSessionResume 用它还原 —— 同一份文件算出同一个 caseId，接得回原会话。
     内置真题（从详情页进）不该有文件题面，一律不收。 */
  var names=[];
  if(!fromDetail){
    var H=getAnalysisHooks(), held=(H&&H.heldList)?H.heldList('home'):[];
    for(var i=0;i<held.length;i++) if(held[i].kind==='problem') names.push(held[i].name);
  }
  analysisPaperNames=names;
  W.begin({
    title: state.workTitle||'',
    protoId: fromDetail?(state.detailId||''):'',
    fromDetail: !!fromDetail,
    paperNames: names
  });
}

/**
 * 把一条任务贴回工作台（页面外壳在「返回工作台」或「刷新后落到 #/work」时调用）。
 *
 * 只恢复上下文与视图，**不重新发起任何 run** —— 上一轮跑没跑完由既有产物决定
 * （analysisBoot 那套 reuse 依旧管用）。容器还在内存里（同一页面内切走切回）就
 * 原样接上，不拿快照覆盖正在跑的内容；不在（刷新过）才用快照铺回来。
 */
function workSessionResume(t){
  if(!t||!t.title) return false;
  state.workTitle=t.title;
  state.detailId=t.protoId||null;
  setAnimSet(t.protoId, true);   /* 恢复会话只切演示集，不清 ANIM_BANK（由恢复逻辑重建）*/
  analysisFromDetail=!!t.fromDetail;   /* 后面每一步取题面都要用它，别留 false */
  /* 还原开题那一刻的题目文件名 —— 暂存区刷新即丢，题面由文件承担的任务靠它接回原会话 */
  analysisPaperNames=Array.isArray(t.paperNames)?t.paperNames:[];
  var wt=$('w-title');
  if(wt) wt.textContent=state.workTitle.length>46?state.workTitle.slice(0,46)+'…':state.workTitle;
  var tag=$('w-tag');
  if(tag) tag.textContent=t.fromDetail?'2026 真题复现':'新题目';
  var host=workHostShow(t.id);
  if(host&&!host.childNodes.length&&t.stream) host.innerHTML=t.stream;

  /* ~接入改动（2026-10-12）：进度条与附件条是**视图级**的骨架，只有 startWork() 画过。
     刷新 / 换任务走的是这一条路，必须补一次 —— 否则顶上那块永远是空的（实测那 67K
     原型 CSS 一直在，丢的就是 #w-steps 与 #attach-strip-work 这两块 DOM）。
     步骤取**本条任务记下的第几步**，不能一律第 0 步 —— 明明停在问题二却高亮
     「1 全局分析」，那是说假话。 */
  if(typeof t.step==='number'&&t.step>=0) curStep=t.step;
  /* ~接入改动（2026-10-12）：右栏闸门区也按任务贴回 —— 闸门现在只活在右栏，
     它不在 t.stream 里，不单独恢复的话刷新/切任务回来右栏会空掉。 */
  railGateLoad(t.id,t.rail);
  workChromeSync();

  /* 分析通道是模块级单例：换到这一条之前先把上一条的全局结果摘掉，
     否则渲染层会把**上一条任务**的分析贴进这一条的对话里。摘完再试接回本条的
     既有产物（reuse 命中就不再烧模型调用；没命中就维持空态，等用户推进流程）。 */
  var H=getAnalysisHooks();
  if(H){
    if(H.resetRuns) H.resetRuns();
    var ref=analysisRef(state.workTitle,!!t.fromDetail);
    if(ref&&H.reuse) H.reuse(ref);
  }

  /* ~接入改动（2026-10-13）：动画通道同为模块级单例，同一个道理 —— 通道里挂着的
     若是**另一道题**的会话，摘掉并接回这一条的既有产物（右栏「演示」面板跟着换）；
     就是本题（含后台还在跑）则不动，面板原样续着画。 */
  var A2=getAnimationHooks();
  if(A2&&A2.reuse){
    var aref=analysisRef(state.workTitle,!!t.fromDetail);
    var acur=A2.currentCase?A2.currentCase():null;
    if(aref&&(!acur||acur.caseId!==aref.caseId)){
      if(A2.reset) A2.reset();
      animRealRevoke();
      ANIM_REAL_ON=false;
      animRailReset();
      A2.reuse(aref).then(function(ok){
        if(ok){ ANIM_REAL_ON=true; ANIM_REAL_TRIED=true; animRealRegister(); animRailRender(); }
      });
    }else{
      if(A2.status&&A2.status()!=='idle') ANIM_REAL_ON=true;
      animRailRender();
    }
  }

  go('work');
  scrollBottom();
  return true;
}

/** 把原型那一整套全局函数还原到 window。
 *
 *  为什么必须这么做：原型的 HTML 里写的是 inline onclick="go('home')" 这类
 *  内联处理器，浏览器只在 window 上找这些名字。Vue 模板沿用同一套 HTML
 *  （这样行为逐字不变），所以函数得回到 window 上。
 *  这也是原型原本的运行时形态（它的函数本来就是全局的）。 */
export function installPrototypeGlobals() {
  const w = window as unknown as Record<string, unknown>
  const fns: Record<string, unknown> = {
    "$": $,
    "esc": esc,
    "hl": hl,
    "toast": toast,
    "fmt": fmt,
    "go": go,
    "pcardHTML": pcardHTML,
    "renderCarousel": renderCarousel,
    "carMeasure": carMeasure,
    "carRender": carRender,
    "carGo": carGo,
    "carMove": carMove,
    "startCarousel": startCarousel,
    "stopCarousel": stopCarousel,
    "frowHTML": frowHTML,
    "renderFilters": renderFilters,
    "setCat": setCat,
    "setYear": setYear,
    "setType": setType,
    "filtered": filtered,
    "renderGrid": renderGrid,
    "useSample": useSample,
    "demoProblem": demoProblem,
    "pickFile": pickFile,
    "sendFromHome": sendFromHome,
    "openDetail": openDetail,
    "setDetailTab": setDetailTab,
    "renderDetailPanels": renderDetailPanels,
    "jumpBook": jumpBook,
    "animSVG": animSVG,
    "renderAnimPanel": renderAnimPanel,
    "switchAnim": switchAnim,
    "renderCodePanel": renderCodePanel,
    "runCode": runCode,
    "supRow": supRow,
    "supportHTML": supportHTML,
    "paperDeliverHTML": paperDeliverHTML,
    "dlvNavHTML": dlvNavHTML,
    "dlvHTML": dlvHTML,
    "setDlvTab": setDlvTab,
    "setUnifiedResultTab": setUnifiedResultTab,
    "downloadPaperPdf": downloadPaperPdf,
    "downloadPaperWord": downloadPaperWord,
    "downloadSupportZip": downloadSupportZip,
    "renderResultPanel": renderResultPanel,
    "openWorkFromDetail": openWorkFromDetail,
    "openMicrogridReplay": openMicrogridReplay,
    "closeMicrogridReplay": closeMicrogridReplay,
    "AR": AR,
    "genAnim": genAnim,
    "refreshAnimDrawer": refreshAnimDrawer,
    "regAnims": regAnims,
    "animShotHTML": animShotHTML,
    "animMeta": animMeta,
    "fsBtnHTML": fsBtnHTML,
    "openAnimFs": openAnimFs,
    "switchAfsTab": switchAfsTab,
    "closeAnimFs": closeAnimFs,
    "unmountAnim": unmountAnim,
    "mountAnim": mountAnim,
    "runAnimScript": runAnimScript,
    "renderAfsTabs": renderAfsTabs,
    "animDone": animDone,
    "pendAnim": pendAnim,
    "animList": animList,
    "setPhase": setPhase,
    "mkey": mkey,
    "scrollBottom": scrollBottom,
    "renderSteps": renderSteps,
    "artHTML": artHTML,
    "actsHTML": actsHTML,
    "fileChipsHTML": fileChipsHTML,
    "msgHTML": msgHTML,
    "pushMsg": pushMsg,
    "openArtOfType": openArtOfType,
    "autoOpenArt": autoOpenArt,
    "thinking": thinking,
    "startWork": startWork,
    "sendInWork": sendInWork,
    "userTurn": userTurn,
    "openArt": openArt,
    "openDrawer": openDrawer,
    "closeDrawer": closeDrawer,
    "drawerAnim": drawerAnim,
    "artBody": artBody,
    "renderMine": renderMine,
    "toggleWork": toggleWork,
    "toggleShare": toggleShare,
    "miniHTML": miniHTML,
    "setMiniTab": setMiniTab,
    "autoGrow": autoGrow,
    'openSandboxRun': openSandboxRun,
    'openSandboxArtifacts': openSandboxArtifacts,
    'attachStripRemove': attachStripRemove,
    'attachRerun': attachRerun,
    'pickProblem': pickProblem,
    'pickData': pickData,
  }
  Object.assign(w, fns)
  w.__smyyPrototype = { installed: true, count: 85 }
}

export const PROTOTYPE_FN_COUNT = 85

/* ── 对话区自由输入（非原型原码）─────────────────────────────────────────

   工作台对话区只有这一个发送口：把用户的一句话原样交给智能体，答案实时画进对话区。
   前端**不做意图粗筛** —— 原来的「流程指令 / 题目相关问答 / 无关话题」分流、
   规范指令按钮与无关话题答复，已随 utils/analysis/intent.ts 一并删除；
   是不是指令、要不要推进流程，由智能体按它自己的 AGENTS.md 判定。 */
/**
 * 把用户的一句话交给智能体 —— 工作台对话区**唯一**的发送口（2026-10-12 重写）。
 *
 * 与读题气泡同构：留一个 .lv-stream 装事件流、700ms 重画一次；平台真闸门
 * （ui_form_advanced / ui_gate）由 syncGate 挂进这条气泡。
 *
 * 它**不推进任何前端流程** —— 前端已经没有流程可推了。用户说「继续」，智能体
 * 自己知道该继续什么；用户只是问一句，它就只答一句。
 */
export function intentAsk(v){
  var H=getAnalysisHooks();
  /* 两态拆开（2026-10-14 用户现场：后端活得好好的，卡的其实是题面）——
     「连不上」只留给通路真的断了（没注册钩子）；题面解不出来要说清是题面的事，
     别再拿「检查登录、后端在不在跑」把人支去查网络。 */
  if(!H||!H.ask){
    pushMsg({who:'ai',html:'<h4>现在连不上智能体</h4>'+
      '<p class="src-note">这条通路要连上智能体才能回答：检查一下登录状态、后端是不是在跑。这一句没有发出去。</p>'},mkey());
    return;
  }
  var ref=analysisRef(state.workTitle,analysisFromDetail);
  if(!ref){
    var t=(state.workTitle||'').slice(0,40);
    pushMsg({who:'ai',html:'<h4>这道题还没有可用的题面</h4>'+
      '<p class="src-note">当前题面「'+esc(t)+'」太短，也拿不到题目文件 —— 这道题的题面原本由题目文件承担，文件在页面刷新后就丢了。回首页把题干写全，或把那份题目文件重新传一次（推荐，能接回原会话）；内置真题请走题库详情页进入。这一句没有发出去。</p>'},mkey());
    return;
  }
  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+
    '<div class="an-head"><h4>智能体正在处理</h4>'+
    '<p class="src-note">这一句已经发给它了 —— 它可能直接回答，也可能反过来问你。</p>'+
    /* 排队行：后端忙时这一句会先排队（同一会话只允许一个活跃 run），
       用户必须看得见它在等什么 —— 否则「输入框吞了消息」和「发不出去」体感一样。
       文案由 analysis 模块给（`askWaitNote`），这里只负责显示/隐藏。 */
    '<p class="src-note ask-wait" style="display:none"></p></div>'+
    liveBoxHTML()+
    '</div></div>';
  workHost().appendChild(el);
  scrollBottom();

  var liveBox=el.querySelector('.lv-stream');
  if(H.liveMount) H.liveMount(liveBox);
  var done=false, waitEl=el.querySelector('.ask-wait'), timer=setInterval(function(){
    if(H.liveRender) H.liveRender(liveBox);
    syncGate(el,H);
    syncSteps();
    /* 排队说明（等后台收尾时才有）—— 等值守卫：内容不变就不碰 DOM */
    if(waitEl&&H.askWaitNote){
      var note=String(H.askWaitNote()||'');
      if(waitEl.textContent!==note){ waitEl.textContent=note; waitEl.style.display=note?'':'none'; }
    }
    /* 这一轮里智能体可能刚写/重写了产物（内部按签名去重，没变就不打接口） */
    refreshArtCard();
    scrollBottomIfStick();
  },700);
  function stop(){ done=true; if(timer){ clearInterval(timer); timer=null; } }
  function say(html){ stop(); pushMsg({who:'ai',html:html},mkey()); }

  H.ask(ref,v).then(function(res){
    if(done) return;
    stop();
    if(H.liveRender) H.liveRender(liveBox);
    /* 有正文就已经画在气泡里了；只有真的一个字都没有才把整条气泡收掉 */
    if(res&&res.ok) return;
    var note=(res&&res.note)||'原因未明';
    if(res&&res.text){ say('<p class="src-note">上面那半段没能收完 —— '+esc(note)+'</p>'); return; }
    el.remove();
    say('<h4>这句话没能发出去</h4><p class="src-note">'+esc(note)+'</p>');
  }).catch(function(e){
    if(done) return;
    stop();
    el.remove();
    say('<h4>这句话没能发出去</h4><p class="src-note">'+esc((e&&e.message)||'调用失败')+'</p>');
  }).then(function(){
    /* ~接入改动（2026-10-14）：这一轮结束后把产物卡重拉一次。
       智能体在这一轮里可能刚写了新版本（逐问分析、重做、继续往下做），
       卡片不重拉就永远停在开题那一刻的清单，用户会误以为「重做没生效」。 */
    refreshArtCard();
  });
}

/**
 * 把对话区那张产物卡重拉一次 —— **按签名去重**。
 *
 * 为什么要去重（2026-10-14）：跟随会话期间这条函数会被 700ms 的进度轮询叫到，
 * 每次真拉一遍产物树就是白打接口。签名（`artifactStamp`）只在产物清单真的变了
 * 时才抬 —— 于是「有新东西落盘」立刻上屏，其余时刻一次网络请求都不发。
 *
 * 为什么需要：产物卡只在「开题那次 run 跑完」挂载并刷过一次（`artifactCardBoot`），
 * 之后的推进全靠对话驱动 —— 没有这一步，智能体重做/续做写下的新版本在清单里看不见。
 * 卡里的树本来就是「本会话 + 全部子会话」的合并，重拉一次就够，不做轮询
 * （2026-10-09 用户拍板：跑完自动刷一次 + 手动刷新）。
 *
 * 只认当前任务那张卡：没有卡（还没走到分析那一步）就什么都不做。
 */
var LAST_ART_STAMP=-1;
function refreshArtCard(){
  var H=getAnalysisHooks();
  if(!H||!H.artifactRefresh) return;
  var stamp=H.artifactStamp?Number(H.artifactStamp())||0:0;
  if(stamp===LAST_ART_STAMP) return;
  /* 2026-10-20：产物卡搬到右栏了，这里也要跟着改口径 —— 否则「跟随期间新落盘的
     产物跟手出现」这条会静默失效（对话区已经没有 .art-card 了） */
  var host=railArtHost(false);
  if(!host) return;
  var box=host.querySelector<HTMLElement>('.art-card');
  if(!box) return;
  var ref=analysisRef(state.workTitle,analysisFromDetail);
  if(!ref) return;
  LAST_ART_STAMP=stamp;
  var p=H.artifactRefresh(box,ref.caseId);
  if(p&&p.then) p.then(function(){ railArtCount(box); });
}

/* ── 工作台右栏：闸门 + 产物（2026-10-12 新增，用户定版）────────────────────
   版式：**左对话 / 右（闸门 + 产物）**。两条人工决策链路都改道到这里 ——
   原型流程闸门（pushFlowGate → railGateOpen）与平台 HITL 闸门（syncGate →
   railGateHost）。对话流里从此不再出现闸门卡片；用户选择/跳过后仍会留一条
   用户消息，所以对话的叙事没有断。

   四个必须守住的点：
     · 右栏不在（单跑原型 / 脚本 / 不在工作台视图）时一律回退老路径 ——
       不能因为新增一块 UI 就让原型逻辑跑不动；
     · 闸门区**按任务**：DOM 是真相，只在换任务时用任务仓里的快照铺回来
       （RAIL_GATE_TASK 记住当前贴的是哪条，避免同一条任务被旧快照覆盖）；
     · 产物清单靠**对账**重画（签名变了才动 innerHTML）—— 每 1.5 秒重写
       会把用户的点击打断；
     · 一律不写内联 onclick：本段是模板字符串，引号会在生成期被解义。 */
var RAIL_GATE_TASK='';    /* 右栏闸门区当前贴的是哪条任务 */
var RAIL_ART_SIG='';      /* 产物清单上次渲染的签名 */
var RAIL_PIN=0;           /* 用户手动切过 tab 的保护期（时间戳） */

/** 当前任务的右栏闸门容器；不在工作台视图 / 右栏不在时返回 null（调用方回退老路径） */
function railGateHost(){
  if(typeof state==='undefined'||!state||state.view!=='work') return null;
  return $('rail-gate-list')||null;
}

/** 闸门待办数：没定格的卡片才计数（同一张画两次也只算一张） */
function railGateBadge(){
  var list=$('rail-gate-list'), n=0;
  if(list){
    var all=list.querySelectorAll('.rail-gate-card');
    for(var i=0;i<all.length;i++){ if(!all[i].classList.contains('decided')) n++; }
  }
  var b=$('rail-gate-badge');
  if(b){ b.textContent=n?String(n):''; b.style.display=n?'':'none'; }
  var t=$('rail-tab-gate');
  if(t) t.classList.toggle('hot',n>0);
}

/** 把一个闸门节点画进右栏；右栏不在时回退原型那条「塞进对话区」的老路 */
function railGateOpen(node,gate){
  var list=railGateHost();
  if(!list) return pushMsg(node,gate.key);
  var el=document.createElement('div');
  el.className='rail-gate-card';
  /* 复用对话气泡那套 .bubble .txt 的观感 —— 内容本来就是同一份 HTML */
  el.innerHTML='<div class="bubble"><div class="txt">'+(node.html||'')+'</div></div>';
  list.appendChild(el);
  list.scrollTop=list.scrollHeight;
  railGateBadge();
  railExpand();
  railTabSoft('gate');
  return node.key;
}

/** 闸门定了（选了 / 跳了 / 交了）：把它那张卡定格成「已确认」 */
function railGateDecide(key,card){
  var el=card||(key?$('gate-'+key):null);
  if(!el||!el.closest) return;
  var wrap=el.closest('.rail-gate-card');
  if(wrap) wrap.classList.add('decided');
  railGateBadge();
}

/** 开新题：右栏的旧闸门不能留着（那是上一条任务的） */
function railGateReset(){
  RAIL_GATE_TASK='';
  var list=$('rail-gate-list');
  if(list) list.innerHTML='';
  railGateBadge();
  /* 记录按任务分键，换了任务就换一份 —— 这里重画一次，旧题那几条自然不出现 */
  gateHistRender();
}

/** 切任务时把这条任务的闸门区贴回来；同一条任务不动 DOM（它才是真相） */
function railGateLoad(taskId,html){
  var list=$('rail-gate-list');
  if(!list) return;
  var id=taskId||'';
  if(RAIL_GATE_TASK===id) return;
  RAIL_GATE_TASK=id;
  list.innerHTML=html||'';
  railGateBadge();
  /* 卡片是从快照贴回来的，闸门记录得按当前任务重读一次 */
  gateHistRender();
}

/* ── 右栏 tab ──────────────────────────────────────────────────────────── */
function railTabSet(name){
  var w=$('wrail'); if(!w) return;
  var tabs=w.querySelectorAll('.rail-tab');
  for(var i=0;i<tabs.length;i++){
    tabs[i].classList.toggle('on',tabs[i].getAttribute('data-rail')===name);
  }
  var g=$('rail-pane-gate'), a=$('rail-pane-art'), n=$('rail-pane-anim');
  if(g) g.classList.toggle('on',name==='gate');
  if(a) a.classList.toggle('on',name==='art');
  /* ~接入改动（2026-10-13）：第三个 tab「演示」—— 切过去顺手把「已到 N 档」徽标清掉 */
  if(n) n.classList.toggle('on',name==='anim');
  if(name==='anim') animRailBadge(0);
}
/** 用户手动切的 tab：给 8 秒保护期，别被自动切换立刻抢走 */
function railTab(name){ RAIL_PIN=Date.now()+8000; railTabSet(name); }
/** 系统自动切（来了闸门 / 打开了产物）：保护期内不动用户的视角 */
function railTabSoft(name){ if(Date.now()<RAIL_PIN) return; railTabSet(name); }
/** 闸门来了就把右栏展开 —— 收着的时候卡片等于没画，用户根本看不到 */
function railExpand(){
  var w=$('wrail');
  if(!w||!w.classList.contains('min')) return;
  w.classList.remove('min');
  var b=$('rail-min');
  if(b) b.textContent='›';
}
function railToggle(){
  var w=$('wrail'); if(!w) return;
  w.classList.toggle('min');
  var b=$('rail-min');
  if(b) b.textContent=w.classList.contains('min')?'‹':'›';
}
function railBind(){
  var w=$('wrail');
  if(!w||w.getAttribute('data-bound')==='1') return;
  w.setAttribute('data-bound','1');
  var tabs=w.querySelectorAll('.rail-tab');
  for(var i=0;i<tabs.length;i++){
    (function(b){ b.addEventListener('click',function(){ railTab(b.getAttribute('data-rail')); }); })(tabs[i]);
  }
  var m=w.querySelector('.rail-min');
  if(m) m.addEventListener('click',railToggle);
}

/* ── 右栏产物浏览 ──────────────────────────────────────────────────────── */
/** 产物预览盖住清单 —— 原型那三个抽屉函数在 core 里被加了钩子调这里 */
function railArtView(on){
  var p=$('rail-pane-art'); if(!p) return;
  p.classList.toggle('preview',!!on);
  if(on) railTabSoft('art');
}
function railArtBadge(n){
  var b=$('rail-art-badge');
  if(b){ b.textContent=n?String(n):''; b.style.display=n?'':'none'; }
}
function bindRailArtRows(){
  var box=$('rail-art-list'); if(!box) return;
  var rows=box.querySelectorAll('.ra-row');
  for(var i=0;i<rows.length;i++){
    (function(r){
      r.addEventListener('click',function(){
        var card=document.getElementById(r.getAttribute('data-card')||'');
        /* 点右栏这一行 = 点对话区那张产物卡（onclick 里就是 openArt），不另写一套打开逻辑 */
        if(card) card.click();
        else toast('这张产物已经不在当前对话里了',IC_WARN);
      });
    })(rows[i]);
  }
}
/**
 * 产物清单：把当前任务对话区里的 .arts .acard 抄一份到右栏。
 * 只扫 .arts 里的卡 —— 左侧那些步骤卡也是 .acard，混进来就全错了。
 */
function railArtSync(){
  railBind();
  var box=$('rail-art-list'); if(!box) return;
  var host=workHostEl();
  var cards=host?host.querySelectorAll('.arts .acard'):[];
  var sig='', items=[];
  for(var i=0;i<cards.length;i++){
    var c=cards[i];
    var nameEl=c.querySelector('.at b'), subEl=c.querySelector('.at span'), icEl=c.querySelector('.ai');
    var it={
      id:c.id||'',
      name:nameEl?(nameEl.textContent||''):'',
      sub:subEl?(subEl.textContent||''):'',
      icon:icEl?(icEl.textContent||''):'',
      bg:icEl?(icEl.style.background||''):'',
      on:c.classList.contains('open')
    };
    sig+=it.id+'|'+it.name+'|'+it.sub+'|'+(it.on?'1':'0')+';';
    items.push(it);
  }
  if(sig!==RAIL_ART_SIG){
    RAIL_ART_SIG=sig;
    var h='';
    if(!items.length){
      h='<p class="ra-empty">还没有产物。分析 / 动画 / 代码跑完后，产物会列在这里，点一下就打开。</p>';
    }else{
      for(var j=0;j<items.length;j++){
        var x=items[j];
        h+='<button type="button" class="ra-row'+(x.on?' on':'')+'" data-card="'+esc(x.id)+'">'
          +'<i class="ra-ic" style="background:'+esc(x.bg||'#12a594')+'">'+esc(x.icon||'')+'</i>'
          +'<span class="ra-t"><b>'+esc(x.name||'产物')+'</b><s>'+esc(x.sub||'')+'</s></span>'
          +'<span class="ra-go">查看</span></button>';
      }
    }
    box.innerHTML=h;
    bindRailArtRows();
  }else{
    /* 只有「当前打开的是哪一张」会变 —— 轻量刷高亮，不重画整块 */
    var rows=box.querySelectorAll('.ra-row');
    for(var k=0;k<rows.length&&k<items.length;k++) rows[k].classList.toggle('on',items[k].on);
  }
  /* 徽标以**主要产物**那份数为准（对话区那批 .acard 是内置剧本时代的卡，真跑时是空的） */
  var mainHost=railArtHost(false);
  var mainCount=mainHost?mainHost.querySelectorAll('.art-item').length:0;
  railArtBadge(mainCount||items.length);
}

/* ══════════════════════════════════════════════════════════════════════════
   右栏三件事（2026-10-20）：闸门可追溯 / 产物搬进右栏 / 「下一步」快捷按钮

   用户原话：
     ①「闸门选项可以追溯查看，可以查看本轮任务出现了哪几个闸门，分别选择了什么」
     ②「产物产出位置从对话内改到对话右侧的产物栏；对话中不显示」
     ③「子问题结束之后没有回应，我不知道已经分析完成在等我说开始下一题」
   三件事的落点：
     · 闸门记录：每次定格（提交 / 取消）抄一条进 localStorage（按任务分键），
       画在闸门区下方，`<details>` 点开看字段明细 —— 新闸门顶掉旧卡片也不丢。
     · 产物：`.art-card` 的宿主从对话气泡换成 `#rail-pane-art` 里的
       `.art-rail-host`；对话区里的旧产物卡用 CSS 收起来。
     · 快捷按钮：`sendQuick` 把一句话塞进输入框走同一条发送口，不另开一条通路。
   ══════════════════════════════════════════════════════════════════════════ */

var RAIL_EXTRA_CSS = [
  '#rail-gate-hist{flex:0 0 auto;max-height:46%;overflow-y:auto;padding:0 12px 12px;background:var(--bg,#f3fafa)}',
  '#rail-gate-hist[data-empty="1"]{display:none}',
  '.gh-head{display:flex;align-items:center;gap:6px;padding:6px 2px 8px;color:#57727c;font-size:12px;font-weight:600}',
  '.gh-head b{padding:0 6px;border-radius:999px;background:#eef6f5;color:#0b6e68;font-size:11px;font-weight:600;line-height:16px}',
  '.gh-item{border:1px solid #e2eeee;border-radius:9px;background:#fff;margin-bottom:6px;overflow:hidden}',
  '.gh-item>summary{display:flex;align-items:center;gap:7px;padding:7px 9px;cursor:pointer;list-style:none;font-size:12.5px;color:#123039}',
  '.gh-item>summary::-webkit-details-marker{display:none}',
  '.gh-dot{flex:0 0 auto;width:6px;height:6px;border-radius:50%;background:#12a594}',
  '.gh-dot.off{background:#c9d6d8}',
  '.gh-t{flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
  '.gh-at{flex:0 0 auto;color:#8fa5a9;font-size:11px}',
  '.gh-body{padding:0 9px 8px;border-top:1px dashed #eaf3f2}',
  '.gh-body p{margin:6px 0 0;font-size:12px;line-height:1.7;color:#3d6570}',
  '.gh-body p b{display:inline-block;min-width:60px;color:#0b6e68;font-weight:600}',
  '.gh-body p.gh-note{color:#8fa5a9}',
  '.art-rail-host{flex:1 1 auto;min-height:0;overflow-y:auto;padding:12px 12px 4px}',
  '.art-rail-host .art-card{margin:0;padding:0;border:0;background:transparent}',
  '#rail-pane-art.preview .art-rail-host{display:none}',
  '#stream .arts{display:none!important}',
  '.quick-row{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0 0}',
  '.quick-row .abtn{font-size:12.5px;padding:5px 11px;border-radius:999px}'
].join('\n');

function ensureRailExtraStyle(){
  if(typeof document==='undefined') return;
  if(document.getElementById('smyy-rail-extra')) return;
  var el=document.createElement('style');
  el.id='smyy-rail-extra';
  el.textContent=RAIL_EXTRA_CSS;
  document.head.appendChild(el);
}

/* ── 产物：右栏宿主 ───────────────────────────────────────────────────── */

/** 右栏「产物」面板里装产物卡的宿主（懒建，插在旧清单前面） */
function railArtHost(create){
  ensureRailExtraStyle();
  var pane=$('rail-pane-art'); if(!pane) return null;
  var el=pane.querySelector('.art-rail-host');
  if(el) return el;
  if(!create) return null;
  el=document.createElement('div');
  el.className='art-rail-host';
  pane.insertBefore(el, pane.firstChild);
  return el;
}

/** 产物 tab 上的数字：以**主要产物**为准（右栏没开就不动） */
function railArtCount(box){
  var host=box||railArtHost(false);
  if(!host) return;
  railArtBadge(host.querySelectorAll('.art-item').length);
}
function railTabBadgeRefresh(){ railArtCount(null); }

/* ── 闸门记录（可追溯） ───────────────────────────────────────────────── */

var GATE_HIST_MAX=40;
function gateHistKey(){
  var k=workKey();
  return k?('smyy.gate_hist.'+k):'';
}
function gateHistLoad(){
  var key=gateHistKey();
  if(!key||typeof localStorage==='undefined') return [];
  try{
    var raw=localStorage.getItem(key);
    if(!raw) return [];
    var list=JSON.parse(raw);
    return Array.isArray(list)?list:[];
  }catch(e){ return []; }
}
function gateHistSave(list){
  var key=gateHistKey();
  if(!key||typeof localStorage==='undefined') return;
  try{ localStorage.setItem(key,JSON.stringify(list||[])); }catch(e){ /* 隐私模式：只活本次会话 */ }
}
function gateHistHost(create){
  ensureRailExtraStyle();
  var pane=$('rail-pane-gate'); if(!pane) return null;
  var el=$('rail-gate-hist');
  if(el) return el;
  if(!create) return null;
  el=document.createElement('div');
  el.id='rail-gate-hist';
  el.setAttribute('data-empty','1');
  pane.appendChild(el);
  return el;
}
function gateHistTime(ts){
  var d=new Date(ts||Date.now());
  function p(n){ return (n<10?'0':'')+n; }
  return p(d.getHours())+':'+p(d.getMinutes());
}
/** 从定格的卡片上把「问了什么 + 选了什么」抄下来 */
function gateCardRecord(card,note){
  var title='人工确认';
  var t=card.querySelector('.gate-t');
  if(t) title=(t.textContent||'').replace(/^\s+|\s+$/g,'')||title;
  var vals={};
  try{ vals=gateReadValues(card)||{}; }catch(e){ vals={}; }
  var items=[], fields=card.querySelectorAll('.gate-field');
  for(var i=0;i<fields.length;i++){
    var f=fields[i];
    var labEl=f.querySelector('span');
    var label=labEl?(labEl.textContent||'').replace(/\*/g,'').replace(/^\s+|\s+$/g,''):'';
    var nodes=f.querySelectorAll('[data-key]');
    var key=nodes.length?nodes[0].getAttribute('data-key'):'';
    if(!label) label=key||('字段'+(i+1));
    var v=key?vals[key]:undefined;
    var text='';
    if(Array.isArray(v)) text=v.join('、');
    else if(v===undefined||v===null) text='';
    else text=String(v);
    if(text==='') text='（未填）';
    items.push({label:label,value:text});
  }
  return {title:title,items:items,note:note||''};
}
function gateHistPush(card,note){
  if(!card) return;
  var rec=gateCardRecord(card,note);
  rec.at=Date.now();
  rec.status=(note&&note.indexOf('已取消')===0)?'cancelled':'submitted';
  var list=gateHistLoad();
  list.unshift(rec);
  if(list.length>GATE_HIST_MAX) list.length=GATE_HIST_MAX;
  gateHistSave(list);
  gateHistRender();
}
function gateHistRender(){
  var host=gateHistHost(true);
  if(!host) return;
  var list=gateHistLoad();
  if(!list.length){
    host.setAttribute('data-empty','1');
    host.innerHTML='';
    return;
  }
  host.removeAttribute('data-empty');
  var h='<div class="gh-head">本轮闸门记录<b>'+list.length+'</b></div>';
  for(var i=0;i<list.length;i++){
    var r=list[i]||{};
    h+='<details class="gh-item"><summary>'+
      '<i class="gh-dot '+(r.status==='cancelled'?'off':'on')+'"></i>'+
      '<span class="gh-t">'+esc(r.title||'人工确认')+'</span>'+
      '<span class="gh-at">'+esc(gateHistTime(r.at))+'</span></summary>'+
      '<div class="gh-body">';
    var items=r.items||[];
    if(!items.length) h+='<p>这一张没有可记录的字段。</p>';
    for(var j=0;j<items.length;j++){
      h+='<p><b>'+esc(items[j].label||'')+'</b>'+esc(items[j].value||'（未填）')+'</p>';
    }
    if(r.note) h+='<p class="gh-note">'+esc(r.note)+'</p>';
    h+='</div></details>';
  }
  host.innerHTML=h;
}

/** 「下一步」按钮：把一句话塞进输入框，走与手打完全同一条发送口 */
export function sendQuick(text){
  var v=String(text||'').replace(/^\s+|\s+$/g,'');
  if(!v) return;
  var ta=$('work-input');
  if(!ta) return;
  ta.value=v;
  ta.style.height='auto';
  sendInWork();
}

/** 给 Vue 外壳用的：右栏对账一次（绑定 + 产物清单 + 闸门记录） */
function railSync(){ ensureRailExtraStyle(); railBind(); railArtSync(); gateHistRender(); }

/**
 * 补齐工作台的「视图级骨架」：顶部六步进度条 + 输入框上方那条附件条（2026-10-12）。
 *
 * 为什么会缺：#view-work 的模板里，进度条 #w-steps 是个**空 div**，附件条
 * #attach-strip-work **根本不在模板里** —— 两块都只有 startWork() 现场画得出来
 * （renderSteps / attachBeginProblem）。而刷新、从别的视图点回工作台、从沙箱返回后
 * 组件重建，走的都是 workSessionResume()，它只贴对话快照就 go('work')，于是这两块
 * 永远缺席：顶上一片空、布局豁一块，用户会当成「样式丢了」。
 * （2026-10-12 实测：那份 67K 原型 CSS 刷新前后逐字节一致 —— 丢的自始至终是这两块
 *  DOM，不是样式表。）
 *
 * 所以把「进工作台要补的骨架」收成这一个幂等函数，由 workSessionResume 每次调用：
 *   · 进度条按当前 curStep 重画（调用方已把它同步成这条任务记下的第几步）——
 *     换任务时它跟着换，不再是上一条任务的残留；
 *   · 附件条按当前暂存清单重画：没有待上传的文件时它就是空节点，与 startWork 同口径
 *     （刷新后暂存区本来就是空的 —— 那些文件早已在会话里，不是被删了）。
 */
function workChromeSync(){
  renderSteps(Math.max(0,Math.min(STEPS.length-1,curStep)));
  attachStripRender('work');
  railSync();
}

/* bridge 里手写的函数默认「既不挂 window 也不 export」—— 它们只服务 core 内部。
   下面这几个是给 Vue 外壳用的：外壳要贴回一条任务、要拿当前对话区容器去存取快照，
   不该再手写一套 DOM 恢复逻辑（那会和这里的容器分桶规则打架）。 */
export { workSessionResume, workHostEl, workSessionDrop, workChromeSync, railSync }
