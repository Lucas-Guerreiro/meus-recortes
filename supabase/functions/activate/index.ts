// Supabase Edge Function: activate
// Responsável por validar chaves de licença com segurança na nuvem

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

serve(async (req) => {
  // Tratar requisição de pre-flight CORS (OPTIONS)
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    // 1. Validar se a requisição é um POST
    if (req.method !== 'POST') {
      return new Response(
        JSON.stringify({ message: "Método não permitido." }),
        { status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const { license_key, device_id } = await req.json()

    if (!license_key || !device_id) {
      return new Response(
        JSON.stringify({ message: "Dados incompletos (chave ou ID do dispositivo ausentes)." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // 2. Inicializar o cliente Supabase com a chave de serviço (Service Role Key)
    // que possui permissões administrativas para ignorar políticas RLS do banco de dados
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""

    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // 3. Consultar a licença
    const { data: licenses, error: fetchError } = await supabase
      .from('licenses')
      .select('*')
      .eq('license_key', license_key.trim())

    if (fetchError) {
      console.error("Erro ao buscar licença no banco:", fetchError)
      return new Response(
        JSON.stringify({ message: "Erro interno no servidor de licenças." }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    const license = licenses?.[0]

    if (!license) {
      return new Response(
        JSON.stringify({ message: "Chave de licença inválida ou inexistente." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // 4. Validar se está ativa
    if (!license.is_active) {
      return new Response(
        JSON.stringify({ message: "Esta licença foi desativada pelo administrador." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // 5. Validar se já pertence a outro dispositivo
    if (license.device_id && license.device_id !== "null" && license.device_id !== device_id) {
      return new Response(
        JSON.stringify({ message: "Esta licença já está vinculada a outro dispositivo." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      )
    }

    // 6. Fazer a vinculação (PATCH/UPDATE) caso esteja livre
    if (!license.device_id) {
      const { error: updateError } = await supabase
        .from('licenses')
        .update({ 
          device_id: device_id, 
          activated_at: new Date().toISOString() 
        })
        .eq('license_key', license_key.trim())

      if (updateError) {
        console.error("Erro ao vincular dispositivo no banco:", updateError)
        return new Response(
          JSON.stringify({ message: "Erro ao registrar ativação no banco de dados." }),
          { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        )
      }
    }

    // Retornar sucesso
    return new Response(
      JSON.stringify({ 
        activated: true,
        message: "Licença ativada e vinculada com sucesso!" 
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )

  } catch (err: any) {
    console.error("Erro na execução da Edge Function:", err)
    return new Response(
      JSON.stringify({ message: "Falha na requisição de ativação." }),
      { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    )
  }
})
