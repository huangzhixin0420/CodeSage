package com.codesage.ide.ui.web

/**
 * 图片附件(从 Web 端拖拽 / 粘贴到聊天输入框)
 *
 * 数据流:
 *   1. JS 端读取 FileReader 为 dataUrl(完整 data: URL,含 base64)
 *   2. 通过 send_message payload 的 images[] 字段发送
 *   3. Kotlin 端解析后,既可注入到 message 文本(markdown image 引用)
 *      也可作为 List<ImageAttachment> 透传到业务层
 *
 * 注意:
 *   - dataUrl 格式:`data:image/png;base64,iVBORw0KGgo...`(大文件可达几 MB)
 *   - 多数多模态模型接受 markdown image 引用或 base64 inline image_url
 *   - 当前实现走 markdown 引用,Agent 透传到 LLM 的 message content
 */
data class ImageAttachment(
    val id: String,
    val mime: String,        // e.g. "image/png", "image/jpeg"
    val dataUrl: String,     // data: URL,含 base64 编码
    val name: String,        // 原始文件名
)
