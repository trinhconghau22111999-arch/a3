package com.desktopmini.launcher

internal val TEXT_EDIT_FN = """
function(del, ins) {
  var el = document.activeElement;
  try {
    while (el && el.tagName === 'IFRAME' && el.contentDocument && el.contentDocument.activeElement) {
      el = el.contentDocument.activeElement;
    }
  } catch (e) {}
  if (!el) return false;
  var doc = el.ownerDocument;
  function stepBack(str, idx, n) {
    while (n > 0 && idx > 0) {
      idx--;
      var c = str.charCodeAt(idx);
      if (c >= 0xDC00 && c <= 0xDFFF && idx > 0) {
        var h = str.charCodeAt(idx - 1);
        if (h >= 0xD800 && h <= 0xDBFF) idx--;
      }
      n--;
    }
    return idx;
  }
  function fire(target) {
    try {
      target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: ins ? 'insertText' : 'deleteContentBackward', data: ins || null }));
    } catch (e) {
      target.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    var v = el.value;
    var s = null, e2 = null;
    try { s = el.selectionStart; e2 = el.selectionEnd; } catch (err) {}
    if (s === null || e2 === null) { s = e2 = v.length; }
    var start = (e2 === s) ? stepBack(v, s, del) : s;
    try {
      el.setRangeText(ins, start, e2, 'end');
    } catch (err) {
      var proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, v.slice(0, start) + ins + v.slice(e2));
    }
    fire(el);
    return true;
  }
  if (el.isContentEditable) {
    for (var k = 0; k < del; k++) doc.execCommand('delete');
    if (ins) doc.execCommand('insertText', false, ins);
    return true;
  }
  return false;
}
"""

// Chép từ app "Duyệt web" (Webandr): tự bấm "Bỏ qua" quảng cáo YouTube, tua nhanh quảng cáo không bỏ qua được
// (tắt tiếng rồi nhảy đến cuối), ẩn banner/quảng cáo hiển thị.
const val YT_ONLY_AD_JS = """
(function(){
 if(window.__wbYtInstalled)return; window.__wbYtInstalled=true;
 if(typeof window.__wbAdSkip==='undefined')window.__wbAdSkip=true;
 var HIDE='.ytp-ad-overlay-container,.ytp-ad-text-overlay,.ytp-ad-image-overlay,.video-ads,#player-ads,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer{display:none!important}';
 var SKIP=['.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-skip-ad-button','.videoAdUiSkipButton','.ytp-ad-skip-button-container button','.ytp-skip-ad button'];
 var mutedByUs=false,fastByUs=false;
 // Nhớ cờ "đã tắt tiếng do quảng cáo" qua các lần nạp trang (bấm Lùi nạp lại trang): nếu không thì cờ mất, video sau đó bị YouTube giữ im lặng
 try{ mutedByUs=sessionStorage.getItem('__wbMuted')==='1'; }catch(e){}
 function tick(){
  try{
   var on=window.__wbAdSkip!==false;
   var st=document.getElementById('__wb_adcss');
   if(on&&!st&&document.documentElement){st=document.createElement('style');st.id='__wb_adcss';st.textContent=HIDE;document.documentElement.appendChild(st);}
   else if(!on&&st){st.parentNode.removeChild(st);}
   var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
   var p=document.querySelector('.html5-video-player');
   var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));
   if(on&&ad){
    for(var i=0;i<SKIP.length;i++){var b=document.querySelector(SKIP[i]);if(b)b.click();}
    if(v){
     if(!v.muted){v.muted=true;mutedByUs=true;try{sessionStorage.setItem('__wbMuted','1')}catch(e){}}
     if(v.duration&&isFinite(v.duration)){v.currentTime=v.duration;}else{v.playbackRate=16;fastByUs=true;}
    }
   }else if(v){
    if(mutedByUs){
     try{var mp=document.getElementById('movie_player'); if(mp&&mp.unMute) mp.unMute();}catch(e){}
     v.muted=false;mutedByUs=false;try{sessionStorage.removeItem('__wbMuted')}catch(e){}
    }
    if(fastByUs){v.playbackRate=1;fastByUs=false;}
   }
  }catch(e){}
 }
 setInterval(tick,250);
})();
"""

// Gắn mọi nút "Tìm kiếm bằng giọng nói" trên trang (YouTube, Google...) sang AndroidVoice. Dò theo aria-label/title
// thay vì class CSS vì nhãn hỗ trợ tiếp cận ít khi đổi giữa các bản cập nhật giao diện. (Học từ repo Y-utubecuatoi.)
const val VOICE_HOOK_JS = """
(function(){
 if(window.__dmVoiceHook)return; window.__dmVoiceHook=true;
 var L=['tìm kiếm bằng giọng nói','tim kiem bang giong noi','search by voice','voice search','search with your voice'];
 function txt(el){return ((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')).toLowerCase();}
 function has(t){for(var i=0;i<L.length;i++){if(t.indexOf(L[i])!==-1)return true;}return false;}
 function hook(){
  Array.prototype.forEach.call(document.querySelectorAll('button,[role="button"],a,div[aria-label],span[aria-label]'),function(el){
   if(el.__dmVoice)return;
   if(!has(txt(el)))return;
   el.__dmVoice=true;
   el.addEventListener('click',function(ev){
    if(window.AndroidVoice){ev.preventDefault();ev.stopPropagation();window.AndroidVoice.startVoiceSearch();}
   },true);
  });
 }
 hook(); setInterval(hook,1000);
 try{new MutationObserver(hook).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
"""

/** Nút "Mở ứng dụng" / "Open app" của YouTube được đổi thành nút "Đăng nhập" (mở trang đăng nhập Google); ẩn banner mời tải app. */
const val HIDE_OPEN_APP_JS = """
(function(){
  if (window.__hideOpenApp) return; window.__hideOpenApp = true;
  var st = document.createElement('style');
  st.textContent = 'ytm-open-app-banner-renderer,ytm-mealbar-promo-renderer,ytm-open-app-button-renderer,.open-app-button{display:none!important}';
  (document.head || document.documentElement).appendChild(st);
  var RX = /^(mở ứng dụng|open app|mở app|open in app)$/i;
  var LOGIN = 'https://accounts.google.com/ServiceLogin?service=youtube&hl=vi&continue=' + encodeURIComponent('https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1');
  function relabel(e){
    var w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) { if (/\S/.test(n.nodeValue)) { n.nodeValue = 'Đăng nhập'; } }
    if (e.getAttribute && e.getAttribute('aria-label')) e.setAttribute('aria-label', 'Đăng nhập');
  }
  function sweep(){
    var els = document.querySelectorAll('a,button,ytm-button-renderer,yt-button-shape');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.__oa) continue;
      var t = (e.innerText || e.getAttribute('aria-label') || '').replace(/\s+/g,' ').trim();
      if (t && RX.test(t)) {
        e.__oa = true;
        relabel(e);
        e.addEventListener('click', function(ev){ ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation(); location.href = LOGIN; }, true);
      }
    }
  }
  var timer = null;
  new MutationObserver(function(){ if (timer) return; timer = setTimeout(function(){ timer = null; sweep(); }, 200); })
    .observe(document.documentElement, {childList:true, subtree:true});
  sweep();
})();
"""

/** TV: phóng video phủ kín khung (ẩn phần còn lại của trang) và cố gắng phát ngay. */
const val TV_FULL_JS = """
(function(){
  if (!window.__tvFullStyle) {
    window.__tvFullStyle = true;
    var st = document.createElement('style');
    st.textContent = 'html,body{overflow:hidden!important;background:#000!important}video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  function go(){
    var v = document.querySelector('video');
    if (v) { try { v.muted = false; var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e){} }
  }
  go();
  if (!window.__tvFullObs) {
    window.__tvFullObs = true;
    var t = null;
    new MutationObserver(function(){ if (t) return; t = setTimeout(function(){ t = null; go(); }, 500); })
      .observe(document.documentElement, {childList:true, subtree:true});
  }
})();
"""

/**
 * YouTube: ô chọn video cho remote. ▲▼ đưa khung xanh qua từng video (trang tự cuộn theo), OK mở video đang có khung.
 * window.__wbNav(d) / window.__wbOk() trả true khi xử lý được; false thì app trả phím về cho trang như cũ.
 */
const val YT_NAV_JS = """
(function(){
 if(window.__wbNavInit) return; window.__wbNavInit=true;
 var ITEM='ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-rich-item-renderer,ytm-media-item,ytm-compact-autoplay-renderer,ytd-compact-video-renderer,ytd-video-renderer,ytd-rich-item-renderer,ytm-playlist-panel-video-renderer,ytd-playlist-panel-video-renderer,ytm-rich-grid-media,ytd-grid-video-renderer';
 var cur=null;
 function vid(a){try{var u=new URL(a.href,location.href);return u.searchParams.get('v')||u.pathname}catch(e){return a.href}}
 function items(){
   var out=[],seen={};
   var as=document.querySelectorAll('a[href*="/watch"],a[href*="/shorts/"]');
   for(var i=0;i<as.length;i++){
     var a=as[i];
     var r=a.getBoundingClientRect(); if(r.width===0||r.height===0) continue;
     var k=vid(a); if(seen[k]) continue; seen[k]=1;
     out.push(a.closest(ITEM)||a);
   }
   return out;
 }
 var sEl=null, onSearch=false;
 function unmarkSearch(){ if(sEl){ sEl.style.outline=''; sEl.style.outlineOffset=''; } sEl=null; onSearch=false; }
 function typing(){
   var a=document.activeElement;
   return !!(a && (a.tagName==='INPUT' || a.tagName==='TEXTAREA' || a.isContentEditable));
 }
 function visible(el){ var r=el.getBoundingClientRect(); return r.width>0 && r.height>0; }
 // Nut / o tim kiem tren dau trang (ban di dong + ban may tinh); bo qua nut tim bang giong noi
 function searchBtn(){
   var sel='ytm-searchbox button,ytm-searchbox input,ytm-search-icon-button button,button#search-icon-legacy,ytd-searchbox input,ytd-searchbox button,input[name=search_query],button,a,[role=button]';
   var els=document.querySelectorAll(sel), fallback=null;
   for(var i=0;i<els.length;i++){
     var el=els[i]; if(!visible(el)) continue;
     var lab=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')+' '+(el.getAttribute('placeholder')||'')).toLowerCase();
     if(/giong noi|giọng nói|voice|speak/.test(lab)) continue;
     var isBox=el.matches('ytm-searchbox button,ytm-searchbox input,ytm-search-icon-button button,button#search-icon-legacy,ytd-searchbox input,ytd-searchbox button,input[name=search_query]');
     var byLab=/tìm kiếm|tim kiem|search/.test(lab);
     var inHead=!!el.closest('ytm-mobile-topbar-renderer,.mobile-topbar-header,header,ytd-masthead,#masthead-container');
     var r=el.getBoundingClientRect();
     if(isBox || (byLab && (inHead || r.top<120))) return el;
   }
   return fallback;
 }
 function markSearch(el){
   if(cur){ cur.style.outline=''; cur.style.outlineOffset=''; cur=null; }
   unmarkSearch();
   sEl=el; onSearch=true;
   el.style.outline='4px solid #00e676'; el.style.outlineOffset='-2px';
   window.scrollTo({left:0,top:0,behavior:'smooth'});
 }
 function focusSearchInput(){
   var i=document.querySelector('input[name=search_query],input[type=search],input.searchbox-input,input#search');
   if(i && visible(i)){ i.focus(); try{i.select();}catch(e){} unmarkSearch(); return true; }
   return false;
 }
 function firstItem(l){
   for(var i=0;i<l.length;i++){ var t=l[i].getBoundingClientRect(); if(t.bottom>0 && t.top<innerHeight) return l[i]; }
   return l[0];
 }
 var anchor=null, followT=0, followLast=0;
 function setOutline(el){
   if(cur && cur!==el){ cur.style.outline=''; cur.style.outlineOffset=''; }
   cur=el; el.style.outline='4px solid #00e676'; el.style.outlineOffset='-4px';
 }
 // Luot 2 ngon: o chon dung yen tren man hinh, video nao truot vao cho do thi o chon chuyen sang video do
 function followNow(){
   followLast=Date.now();
   if(onSearch || typing() || !cur || !cur.isConnected) return;
   if(!anchor){
     var c=cur.getBoundingClientRect();
     anchor={x:Math.max(0,Math.min(innerWidth-1,c.left+c.width/2)), y:Math.max(0,Math.min(innerHeight-1,c.top+c.height/2))};
   }
   var list=items(), best=null, bestD=Infinity;
   for(var i=0;i<list.length;i++){
     var r=list[i].getBoundingClientRect();
     if(r.bottom<=0||r.top>=innerHeight) continue;
     var inside=anchor.x>=r.left&&anchor.x<=r.right&&anchor.y>=r.top&&anchor.y<=r.bottom;
     var dx=r.left+r.width/2-anchor.x, dy=r.top+r.height/2-anchor.y;
     var dd=inside?-1:(dx*dx+dy*dy);
     if(dd<bestD){ bestD=dd; best=list[i]; }
   }
   if(best && best!==cur) setOutline(best);
 }
 window.__wbFollow=function(){
   if(!cur) return;
   var now=Date.now();
   if(now-followLast>=80){ if(followT){clearTimeout(followT);followT=0;} followNow(); }
   else if(!followT){ followT=setTimeout(function(){ followT=0; followNow(); }, 80-(now-followLast)); }
 };
 function mark(el){
   anchor=null;
   unmarkSearch();
   if(cur && cur!==el){ cur.style.outline=''; cur.style.outlineOffset=''; }
   cur=el;
   if(el){
     el.style.outline='4px solid #00e676'; el.style.outlineOffset='-4px';
     el.scrollIntoView({block:'center',inline:'nearest',behavior:'smooth'});
     if(window.scrollX) window.scrollTo({left:0,top:window.scrollY,behavior:'auto'});
   }
 }
 window.__wbNav=function(d){
   if(typing()) return false;          // dang go chu: ◀▶ dich con tro chu nhu binh thuong
   if(onSearch) return true;
   var list=items(); if(!list.length) return false;
   if(cur && !cur.isConnected) cur=null;
   var idx=cur?list.indexOf(cur):-1;
   if(idx<0){
     idx=0;
     for(var i=0;i<list.length;i++){var t=list[i].getBoundingClientRect(); if(t.bottom>0 && t.top<innerHeight){idx=i;break;}}
     mark(list[idx]); return true;
   }
   var n=idx+d;
   if(n<0){ return true; }
   if(n>=list.length){ window.scrollBy(0, innerHeight*0.6); return true; }
   mark(list[n]); return true;
 };
 // LEN / XUONG: sang video o hang tren / hang duoi (tim theo vi tri tren man hinh, uu tien video thang cot).
 // Khong co video o hang do thi cuon trang len / xuong.
 window.__wbVert=function(d){
   if(typing()){                        // dang go chu: ▼ thoat o go, chon video; ▲ khong lam gi
     if(d>0){ document.activeElement.blur(); var l0=items(); if(l0.length) mark(firstItem(l0)); }
     return true;
   }
   var list=items();
   if(onSearch){                        // dang o nut tim kiem: ▼ ve hang video dau
     if(d>0){ unmarkSearch(); if(list.length) mark(firstItem(list)); }
     return true;
   }
   if(!list.length){ if(d<0){ var sb0=searchBtn(); if(sb0){ markSearch(sb0); return true; } } return false; }
   if(cur && !cur.isConnected) cur=null;
   var idx=cur?list.indexOf(cur):-1;
   if(idx<0){ return window.__wbNav(0); }
   var c=cur.getBoundingClientRect(), cx=c.left+c.width/2, cy=c.top+c.height/2;
   var minDy=Math.max(4, c.height*0.5), best=-1, bestS=Infinity;
   for(var i=0;i<list.length;i++){
     if(i===idx) continue;
     var r=list[i].getBoundingClientRect();
     var x=r.left+r.width/2, y=r.top+r.height/2;
     var dy=(y-cy)*d;
     if(dy<minDy) continue;
     var s=dy+Math.abs(x-cx)*2;
     if(s<bestS){ bestS=s; best=i; }
   }
   if(best<0){
     if(d<0){ var sb=searchBtn(); if(sb){ markSearch(sb); return true; } }   // hang tren cung + ▲ = len nut tim kiem
     window.scrollBy(0, d*innerHeight*0.6); return true;
   }
   mark(list[best]); return true;
 };
 // Vao trang ket qua tim kiem (go xong / tim bang giong noi): o chon nam san o video dau tien
 var lastRes='';
 setInterval(function(){
   try{
     if(location.pathname.indexOf('/results')===0 && location.href!==lastRes){
       var l=items(); if(!l.length) return;
       lastRes=location.href;
       var a=document.activeElement; if(a && a.tagName==='INPUT') a.blur();
       mark(firstItem(l));
     }
   }catch(e){}
 },400);
 window.__wbOk=function(){
   if(typing()) return false;
   if(onSearch && sEl){                 // OK tren nut tim kiem: mo trang tim kiem va dat con tro chu vao o nhap
     var t=sEl;
     if(t.tagName==='INPUT'){ t.focus(); try{t.select();}catch(e){} unmarkSearch(); return true; }
     t.click();
     setTimeout(focusSearchInput,350); setTimeout(focusSearchInput,900); setTimeout(focusSearchInput,1600);
     return true;
   }
   if(!cur || !cur.isConnected) return false;
   var a=cur.tagName==='A'?cur:cur.querySelector('a[href*="/watch"],a[href*="/shorts/"]');
   if(!a) return false;
   a.click(); return true;
 };
})();
"""

/**
 * YouTube: ẩn toàn bộ phần Shorts (kệ Shorts trên trang chủ / tìm kiếm, thẻ video dẫn tới /shorts/).
 * Ẩn bằng CSS cho các kệ Shorts đã biết, và ẩn thêm từng thẻ có liên kết /shorts/ (theo dõi thay đổi trang để bắt nội dung tải thêm).
 * Không chạy ở trang đang xem Shorts, và không đụng trang xem video thường.
 */
const val HIDE_SHORTS_JS = """
(function(){
 if(window.__wbNoShorts) return; window.__wbNoShorts=true;
 var SHELF='ytm-reel-shelf-renderer,ytd-reel-shelf-renderer,ytm-reel-item-renderer,ytd-reel-item-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,ytm-pivot-bar-item-renderer[aria-label*="Shorts"]';
 var CARD='ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-rich-item-renderer,ytm-media-item,ytm-rich-grid-media,ytd-rich-item-renderer,ytd-video-renderer,ytd-compact-video-renderer,ytd-grid-video-renderer,ytd-rich-grid-media';
 var st=document.createElement('style'); st.id='__wbNoShortsCss';
 st.textContent=SHELF+'{display:none!important}';
 (document.head||document.documentElement).appendChild(st);
 function hide(el){ if(el && el.style) el.style.setProperty('display','none','important'); }
 function sweep(){
   if(location.pathname.indexOf('/shorts')===0) return;
   try{
     var sh=document.querySelectorAll(SHELF);
     for(var i=0;i<sh.length;i++) hide(sh[i]);
     var as=document.querySelectorAll('a[href*="/shorts/"]');
     for(var j=0;j<as.length;j++){ hide(as[j].closest(CARD+','+SHELF) || as[j]); }
   }catch(e){}
 }
 var t=null;
 function later(){ if(t) return; t=setTimeout(function(){ t=null; sweep(); },300); }
 sweep();
 try{ new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true}); }catch(e){}
 })();
"""

/**
 * YouTube KHÔNG BAO GIỜ bị tắt tiếng ngay lúc video mới bắt đầu: ở trang xem video (/watch, /shorts), trong 2 giây đầu
 * mỗi khi một video bắt đầu được nạp/phát (lần đầu vào, quay lại video trước...), nếu video đang im thì bật tiếng lại.
 * Sau 2 giây đầu thì không kiểm tra nữa, nên người dùng tự tắt tiếng / kéo âm lượng về 0 vẫn được tôn trọng.
 * Lúc quảng cáo đang được tua thì để yên (đã có đoạn tắt tiếng quảng cáo).
 */
const val YT_NOMUTE_JS = """
(function(){
 if(window.__wbNoMute) return; window.__wbNoMute=true;
 function watching(){ var p=location.pathname; return p.indexOf('/watch')===0||p.indexOf('/shorts')===0; }
 function adShowing(){ var p=document.querySelector('.html5-video-player'); return !!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'))); }
 function fix(v){
   try{
     if(!v||!watching()||adShowing()) return;
     var mp=document.getElementById('movie_player');
     if(v.muted||(mp&&mp.isMuted&&mp.isMuted())){ if(mp&&mp.unMute) mp.unMute(); v.muted=false; }
     if(v.volume===0){ if(mp&&mp.setVolume) mp.setVolume(100); v.volume=1; }
   }catch(e){}
 }
 function main(){ return document.querySelector('video.html5-main-video')||document.querySelector('video'); }
 // YouTube hay lưu trạng thái tắt tiếng / âm lượng 0 của lần trước: xóa để video mới không bắt đầu trong im lặng
 try{
   var k='yt-player-volume', raw=localStorage.getItem(k);
   if(raw){ var o=JSON.parse(raw), dd=JSON.parse(o.data); if(dd.muted||!dd.volume){ dd.muted=false; dd.volume=100; o.data=JSON.stringify(dd); localStorage.setItem(k,JSON.stringify(o)); } }
 }catch(e){}
 // Chi kiem tra trong 2 giay dau moi khi mot video bat dau (doi src = video moi; play lai cung video thi bo qua)
 var curSrc=null, timer=null, until=0;
 function stopWin(){ if(timer){ clearInterval(timer); timer=null; } }
 function startWin(v){
   stopWin(); until=Date.now()+2000; fix(v);
   timer=setInterval(function(){ if(Date.now()>=until){ stopWin(); return; } fix(v); },250);
 }
 function onStart(e){
   var v=e.target; if(!v||v.tagName!=='VIDEO') return;
   var src=v.currentSrc||v.src||'';
   if(e.type==='play' && src===curSrc) return;
   curSrc=src; startWin(v);
 }
 ['loadedmetadata','play'].forEach(function(n){ document.addEventListener(n,onStart,true); });
 var first=main(); if(first && !first.paused){ onStart({type:'play',target:first}); }
})();
"""
