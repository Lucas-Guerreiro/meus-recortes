// API Vercel Serverless Function: api/webhook-mp.js
// Escuta eventos de pagamentos aprovados do Mercado Pago e ativa/renova a licença no Supabase

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { action, data, type } = req.body;
    const paymentId = (data && data.id) || req.query['data.id'] || req.query.id;

    if (!paymentId) {
        return res.status(200).send('OK (Sem ID)');
    }

    const MP_ACCESS_TOKEN = process.env.MERCADO_PAGO_ACCESS_TOKEN;
    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!MP_ACCESS_TOKEN || !SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Variáveis ausentes' });
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

        // Só processa se o status for aprovado
        if (payment.status === 'approved') {
            const customerEmail = payment.payer.email.trim().toLowerCase();

            // 2. Busca se este e-mail já possui licença (Renovação)
            const checkUserEmail = await fetch(`${SUPABASE_URL}/rest/v1/licenses?email=eq.${encodeURIComponent(customerEmail)}&select=*`, {
                method: 'GET',
                headers: {
                    'apikey': SUPABASE_SERVICE_ROLE_KEY,
                    'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`
                }
            });

            if (checkUserEmail.ok) {
                const data = await checkUserEmail.json();
                if (data && data.length > 0) {
                    const existingLicense = data[0];
                    const licenseKey = existingLicense.license_key;
                    
                    let novoActivatedAt = new Date();
                    
                    if (existingLicense.activated_at) {
                        const activatedDate = new Date(existingLicense.activated_at);
                        const now = new Date();
                        const diffTime = now - activatedDate;
                        const diffDays = diffTime / (1000 * 60 * 60 * 24);
                        
                        if (diffDays < 30) {
                            const diasRestantes = 30 - diffDays;
                            novoActivatedAt = new Date();
                            novoActivatedAt.setDate(novoActivatedAt.getDate() + diasRestantes);
                        }
                    }

                    // PATCH na licença existente
                    await fetch(`${SUPABASE_URL}/rest/v1/licenses?license_key=eq.${licenseKey}`, {
                        method: 'PATCH',
                        headers: {
                            'apikey': SUPABASE_SERVICE_ROLE_KEY,
                            'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
                            'Content-Type': 'application/json'
                        },
                        body: JSON.stringify({
                            is_active: true,
                            activated_at: novoActivatedAt.toISOString()
                        })
                    });

                    console.log(`Licença ${licenseKey} renovada com sucesso via Webhook.`);
                    return res.status(200).send('OK (Renovado)');
                }
            }

            // 3. E-mail novo! Gerar licença determinística
            const payIdStr = String(paymentId);
            const p1 = payIdStr.substring(0, 4).padEnd(4, 'X');
            const p2 = payIdStr.substring(4, 8).padEnd(4, 'Y');
            const p3 = (payIdStr.substring(8) + 'AB').substring(0, 4).padEnd(4, 'Z');
            const licenseKey = `MR-${p1}-${p2}-${p3}`.toUpperCase();

            // Insere nova licença
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
                    activated_at: null,
                    email: customerEmail
                })
            });

            if (createLicense.ok) {
                console.log(`Licença ${licenseKey} criada para e-mail ${customerEmail} via Webhook.`);
            }
        }

        return res.status(200).send('OK');

    } catch (error) {
        console.error('Erro no webhook-mp:', error);
        return res.status(500).send('Erro interno');
    }
}
