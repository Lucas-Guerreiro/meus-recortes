// API Vercel Serverless Function: api/check-payment.js
// Verifica o status do pagamento no Mercado Pago e ativa a licença no Supabase

export default async function handler(req, res) {
    const { id } = req.query;

    if (!id) {
        return res.status(400).json({ error: 'ID do pagamento não informado' });
    }

    const MP_ACCESS_TOKEN = process.env.MERCADO_PAGO_ACCESS_TOKEN;
    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!MP_ACCESS_TOKEN || !SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Variáveis de ambiente do Supabase ou Mercado Pago ausentes no servidor' });
    }

    try {
        // 1. Consulta o status do pagamento no Mercado Pago
        const mpResponse = await fetch(`https://api.mercadopago.com/v1/payments/${id}`, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${MP_ACCESS_TOKEN}`
            }
        });

        if (!mpResponse.ok) {
            return res.status(500).json({ error: 'Erro ao consultar pagamento no Mercado Pago' });
        }

        const payment = await mpResponse.json();

        // Se o pagamento ainda não foi aprovado, retorna pendente
        if (payment.status !== 'approved') {
            return res.status(200).json({ status: payment.status });
        }

        // 2. Pagamento aprovado! Gerar chave determinística baseada no ID do pagamento
        // Exemplo: pagamento ID 1234567890 -> MR-1234-5678-90AB
        const payIdStr = String(id);
        const p1 = payIdStr.substring(0, 4).padEnd(4, 'X');
        const p2 = payIdStr.substring(4, 8).padEnd(4, 'Y');
        const p3 = (payIdStr.substring(8) + 'AB').substring(0, 4).padEnd(4, 'Z');
        const licenseKey = `MR-${p1}-${p2}-${p3}`.toUpperCase();

        // 3. Verifica no Supabase se essa licença já foi criada
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
                // Já existe, apenas retorna ao cliente
                return res.status(200).json({ status: 'approved', license_key: licenseKey });
            }
        }

        // 4. Se não existe, cria a licença ativa no Supabase
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
            return res.status(200).json({ status: 'approved', license_key: licenseKey });
        } else {
            const errText = await createLicense.text();
            console.error('Erro ao inserir licença no Supabase:', errText);
            return res.status(500).json({ error: 'Erro ao persistir licença no banco de dados' });
        }

    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
