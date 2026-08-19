/**
 * 景威AI键盘 - 可选 AI 代理（Cloudflare Worker 示例）
 *
 * Secrets / variables:
 *   OPENAI_API_KEY = your OpenAI API key
 *   APP_TOKEN      = a random token used by your phone app
 *   OPENAI_MODEL   = optional, e.g. gpt-5-mini
 */

export default {
  async fetch(request, env) {
    if (request.method !== 'POST') {
      return json({ error: 'POST only' }, 405);
    }

    const auth = request.headers.get('Authorization') || '';
    if (env.APP_TOKEN && auth !== `Bearer ${env.APP_TOKEN}`) {
      return json({ error: 'Unauthorized' }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: 'Invalid JSON' }, 400);
    }

    const mode = String(body.mode || 'reply');
    const text = String(body.text || '').slice(0, 6000);
    const style = String(body.style || '').slice(0, 2000);
    if (!text.trim()) return json({ error: 'text is required' }, 400);

    const taskMap = {
      reply: '根据【最近对话上下文】和【当前需要处理的消息】生成3条可以直接发送的高情商中文回复。必须承接前文人物、话题和语气，不要把最后一句当成孤立消息；上下文没有的信息不要脑补。要真诚、自然、有分寸，不油腻，不端着。三条风格明显不同。',
      moments: '根据素材生成3条可以直接发朋友圈的中文文案。分别偏：故事共情、简洁自然、个人成长/IP。不要鸡汤堆砌，不要AI腔。',
      customer: '根据【最近对话上下文】和【当前需要处理的消息】生成3条可以直接发送的客户回复。必须承接前文需求、异议和已说过的信息，先理解和回应对方，再自然推进下一步；不要重复问已经知道的问题，不要虚假承诺，不要强压成交。',
      polish: '把这段中文润色成3个版本：自然保留原意、更加有温度、更加简洁。不要改变事实。'
    };

    const developer = `你是“景威AI键盘”的中文沟通助手。${taskMap[mode] || taskMap.reply}\n用户长期表达偏好：${style || '自然、真诚、温暖、有分寸，少AI味。'}\n只输出严格JSON：{"candidates":["...","...","..."]}，不要Markdown，不要额外解释。`;

    const openaiResp = await fetch('https://api.openai.com/v1/responses', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${env.OPENAI_API_KEY}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: env.OPENAI_MODEL || 'gpt-5-mini',
        input: [
          { role: 'developer', content: [{ type: 'input_text', text: developer }] },
          { role: 'user', content: [{ type: 'input_text', text }] }
        ]
      })
    });

    const data = await openaiResp.json();
    if (!openaiResp.ok) {
      return json({ error: 'OpenAI error', detail: data }, 502);
    }

    const outputText = extractOutputText(data);
    try {
      const parsed = JSON.parse(stripCodeFence(outputText));
      const candidates = Array.isArray(parsed.candidates)
        ? parsed.candidates.map(String).map(s => s.trim()).filter(Boolean).slice(0, 3)
        : [];
      if (!candidates.length) throw new Error('No candidates');
      return json({ candidates });
    } catch (e) {
      return json({ error: 'Model returned invalid JSON', raw: outputText }, 502);
    }
  }
};

function extractOutputText(data) {
  if (typeof data.output_text === 'string' && data.output_text) return data.output_text;
  const chunks = [];
  for (const item of data.output || []) {
    for (const c of item.content || []) {
      if (typeof c.text === 'string') chunks.push(c.text);
    }
  }
  return chunks.join('\n');
}

function stripCodeFence(s) {
  return String(s || '')
    .replace(/^```(?:json)?\s*/i, '')
    .replace(/\s*```$/, '')
    .trim();
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' }
  });
}
