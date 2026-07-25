// API Vercel Serverless Function: api/webhook-mp.js
// Escuta eventos de pagamentos aprovados do Mercado Pago e gera a licença em segundo plano

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { action, data, type } = req.body;

    // O Mercado Pago envia notificações com o tipo de recurso. Nos interessa "payment"
    const paymentId = (data && data.id) || req.query['data.id'] || req.query.id;

    if (!paymentId) {
        return res.status(200).send('OK (Sem ID de pagamento)');
    }

    const MP_ACCESS_TOKEN = process.env.MERCADO_PAGO_ACCESS_TOKEN;
    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!MP_ACCESS_TOKEN || !SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Variáveis de ambiente ausentes' });
    }

    try {
        // 1. Consulta o pagamento no Mercado Pago
        const mpResponse = await fetch(`https://api.mercadopago.com/v1/payments/${paymentId}`, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${MP_ACCESS_TOKEN}`
            }
        });

        if (!mpResponse.ok) {
            return res.status(500).json({ error: 'Erro ao consultar pagamento' });
        }

        const payment = await mpResponse.json();

        // Só gera a licença se o pagamento foi aprovado
        if (payment.status === 'approved') {
            const payIdStr = String(paymentId);
            const p1 = payIdStr.substring(0, 4).padEnd(4, 'X');
            const p2 = payIdStr.substring(4, 8).padEnd(4, 'Y');
            const p3 = (payIdStr.substring(8) + 'AB').substring(0, 4).padEnd(4, 'Z');
            const licenseKey = `MR-${p1}-${p2}-${p3}`.toUpperCase();

            // Verifica se a licença já existe
            const checkSupabase = await fetch(`${SUPABASE_URL}/rest/v1/licenses?license_key=eq.${licenseKey}&select=*`, {
                method: 'GET',
                headers: {
                    'apikey': SUPABASE_SERVICE_ROLE_KEY,
                    'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`
                }
            });

            if (checkSupabase.ok) {
                const data = await checkSupabase.json();
                if (data && data.length > 0) {
                    return res.status(200).send('OK (Licença já existia)');
                }
            }

            // Cria a licença no Supabase
            const createLicense = await fetch(`${SUPABASE_URL}/rest/v1/licenses`, {
                method: 'POST',
                headers: {
                    'apikey': SUPABASE_SERVICE_ROLE_KEY,
                    'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
                    'Content-Type': 'application/json',
                    'Prefer': 'return=minimal'
                },
                body: JSON.stringify({
                    license_key: licenseKey,
                    is_active: true,
                    device_id: null,
                    activated_at: null
                })
            });

            if (createLicense.ok) {
                console.log(`Licença ${licenseKey} ativada com sucesso pelo webhook.`);
            }
        }

        return res.status(200).send('OK');

    } catch (error) {
        console.error('Erro no webhook-mp:', error);
        return res.status(500).send('Erro interno');
    }
}
