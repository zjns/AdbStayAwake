# 终端晨光图标

采用用户确认的方案 A：青色终端符号与暖黄色太阳，表达开发调试与保持唤醒。

- [审核原图](icon-approved.png)：内置 `image_gen` 生成的方案 A。
- [透明前景](../app/src/main/res/drawable-nodpi/ic_launcher_mark.png)：通过内置 `image_gen` 去除底板并清理边缘，用于实际 APK。
- [遮罩预览](icon-preview.png)：圆形、圆角方形和单色样式，下方为 48px 样例。
- [预览页面](icon-preview.html)：以资源的相同比例呈现图层；通过本地 HTTP 服务打开，以允许浏览器读取单色图标的遮罩图片。预览是 CSS 示意，不是设备截图。

Android 资源使用独立的深蓝渐变背景与透明位图前景，由系统施加最终遮罩。前景通过百分比 inset 保留安全留白；在 108dp 图层坐标中，主要不透明像素距中心最远约 30dp。`monochrome` 复用前景的透明轮廓，供 Android 13 及以上版本的主题图标使用。配置参考 [Android 自适应图标文档](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。项目最低版本为 Android 8.0，因此直接使用 `mipmap-anydpi` 自适应图标资源。

本次检查：Debug/Release 构建、Lint、APK 图标及 monochrome 资源引用、前景 alpha 和安全区域、遮罩与小尺寸预览。独立 `app_process` 原生离屏预览受模拟器字体初始化限制未能完成，未据此声称已通过设备桌面显示验证。

## 生成提示词

生成方式：内置 `image_gen`；未使用 CLI/API 回退。以下保留原图及前景派生提示词，方便后续修改。

### 原图

Use case: logo-brand. Create ONE exceptionally polished Android launcher icon concept for 'ADB Stay Awake', an LSPosed developer utility that keeps a phone awake while connected for ADB debugging. Preview for design approval. Square 1024x1024 composition. An elegantly rounded dark midnight-navy squircle fills most of the canvas, on a quiet pale neutral background with only a narrow even outer margin. Main emblem: a large, bold, beautifully proportioned cyan terminal prompt chevron and short cursor stroke, cleverly combined with a small warm sunrise disk with three short rays in its upper right, communicating code/debugging plus staying awake. Make the two ideas feel like one compact designed mark, not clip-art pasted together. Crisp vector-like geometry, generous negative space, careful optical balance, mostly flat with a very restrained luminous cyan-to-teal material gradient. Clean premium open-source developer tool identity. The symbol must remain unmistakable at 48px. Front-facing orthographic, no perspective. No typography except the abstract terminal prompt glyph. No words, no ADB letters, no labels, no watermark, no Android mascot, no battery, no charging plug or lightning bolt, no mockup phone, no decorative scenery, no tiny details. Produce just the single icon.

### 前景提取

Use case: background-extraction. Image 1 is the exact approved Android app icon, an edit target. Produce its production foreground layer on a genuinely transparent background. Remove ONLY the entire dark navy rounded-square background AND the pale exterior margin. Preserve the cyan-to-teal terminal chevron, cyan-to-teal horizontal cursor, golden-orange sun disk, and all three yellow sun rays as faithfully as possible: identical geometry, thickness, rounded ends, relative layout, colors, and smooth subtle gradients. Keep the original full square canvas and the original positions and scale of the foreground symbols; do not crop tightly, do not enlarge or rearrange them. No navy pixels or backing tile should remain between or behind the symbols. Clean transparent negative space, clean anti-aliased edges; remove background glow halos so alpha belongs only to the solid symbols. No words, no new details, no added drop shadow, no new background. This is an asset extraction, not a redesign. Output a single square transparent PNG foreground layer.

### 前景清理

Use case: precise-object-edit. Clean up this transparent Android icon foreground for production. Keep exactly the same cyan/teal terminal chevron and horizontal cursor, yellow/orange sun circle and three rays, their relative position, shape, scale, color gradients, and square canvas. Change ONLY the edges: eliminate every ragged fringe, stray pixel, glow remnant, protrusion, speckle and irregularity around the shapes. Replace their boundaries with perfectly smooth geometric vector-like contours, round caps, clean circle, regular stroke widths and immaculate anti-aliasing. The result must look like a pristine vector export at high resolution, not a photographed or roughly cut out object. Keep all negative space genuinely transparent, including gaps between shapes; no background whatsoever, no black fill, no shadows, no extra elements or text. Do not crop or enlarge the composition.
