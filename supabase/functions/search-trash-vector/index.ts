import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type, sigungu-code',
}

serve(async (req) => {
  // CORS Preflight 처리
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const sigunguCode = req.headers.get("sigungu-code") || "1100000000"; // 디폴트 시군구 코드
    
    // 1. 요청 바디에서 Raw Image Binary 데이터 읽기
    const arrayBuffer = await req.arrayBuffer();
    if (arrayBuffer.byteLength === 0) {
      return new Response(JSON.stringify({ error: "이미지 바이트 데이터가 비어 있습니다." }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    const imageBytes = new Uint8Array(arrayBuffer);
    
    // Binary 데이터를 Base64 문자열로 변환 (Gemini API 페이로드용)
    let binary = "";
    const len = imageBytes.byteLength;
    for (let i = 0; i < len; i++) {
      binary += String.fromCharCode(imageBytes[i]);
    }
    const base64Image = btoa(binary);

    // 2. Gemini 1.5 Flash API 호출 (초고속 사물 단어 추출)
    const geminiKey = Deno.env.get("GEMINI_API_KEY");
    if (!geminiKey) {
      return new Response(JSON.stringify({ error: "GEMINI_API_KEY 설정이 누락되었습니다." }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    console.log("[Info] Gemini 1.5 Flash 이미지 분석 시작...");
    const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=${geminiKey}`;
    
    const geminiPayload = {
      contents: [
        {
          parts: [
            { text: "이 이미지는 쓰레기 분리수거를 위해 카메라로 촬영한 물체입니다. 이 물체가 분리수거 항목 중 무엇인지 딱 하나의 품목 명사 단어로만 답변해줘. (예: 페트병, 종이컵, 캔, 요구르트병, 유리병, 폐형광등 등. 부가 설명이나 온점 및 문장 절대 금지)" },
            {
              inlineData: {
                mimeType: "image/jpeg",
                data: base64Image
              }
            }
          ]
        }
      ],
      generationConfig: {
        maxOutputTokens: 10,
        temperature: 0.1
      }
    };

    const geminiResp = await fetch(geminiUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(geminiPayload)
    });

    if (!geminiResp.ok) {
      const errText = await geminiResp.text();
      console.error("[Error] Gemini API 실패:", errText);
      return new Response(JSON.stringify({ error: `Gemini 분석 실패: ${errText}` }), {
        status: 502,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    const geminiData = await geminiResp.json();
    const detectedItem = geminiData.candidates?.[0]?.content?.parts?.[0]?.text?.trim() || "";
    console.log(`[Success] Gemini 분석 완료: "${detectedItem}"`);

    if (!detectedItem) {
      return new Response(JSON.stringify({ results: [] }), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    // 3. 더 이상 클라우드 DB를 조회하지 않고, Gemini가 검출한 품목 단어 자체를 바로 반환합니다.
    // 모바일 앱은 이 item_name을 받아서 로컬 고정밀 SQLite DB에서 검색을 수행하게 됩니다.
    const results = [{
      id: 1,
      item_name: detectedItem,
      category: "재활용",
      disposal_method: "",
      disposal_time: "",
      similarity: 1.0 // 0.35 필터 무조건 통과
    }];

    console.log(`[Success] 사물 검출 성공: "${detectedItem}"`);

    return new Response(JSON.stringify({ results }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    });

  } catch (err) {
    console.error("[Error] Edge Function 에러:", err);
    return new Response(JSON.stringify({ error: err.message }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" }
    });
  }
})
