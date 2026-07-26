// API Vercel Serverless Function: api/check-payment.js
// Verifica o status do pagamento no Mercado Pago e ativa/renova a licença no Supabase

export default async function handler(req, res) {
    const { id } = req.query;

    if (!id) {
        return res.status(400).json({ error: 'ID do pagamento não informado' });
    }

    const MP_ACCESS_TOKEN = process.env.MERCADO_PAGO_ACCESS_TOKEN;
    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!MP_ACCESS_TOKEN || !SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Variáveis de ambiente do Supabase ou Mercado Pago ausentes' });
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

        const customerEmail = payment.payer.email.trim().toLowerCase();

        // 2. Busca se este e-mail já possui alguma licença cadastrada (Renovação)
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
                
                // Cálculo de renovação acumulativa:
                let novoActivatedAt = new Date(); // Valor padrão se estiver expirado
                
                if (existingLicense.activated_at) {
                    const activatedDate = new Date(existingLicense.activated_at);
                    const now = new Date();
                    const diffTime = now - activatedDate;
                    const diffDays = diffTime / (1000 * 60 * 60 * 24);
                    
                    if (diffDays < 30) {
                        // Ainda estava ativo! Acumula os dias restantes
                        const diasRestantes = 30 - diffDays;
                        // O novo activated_at é jogado para a frente para acumular os dias restantes
                        novoActivatedAt = new Date();
                        novoActivatedAt.setDate(novoActivatedAt.getDate() + diasRestantes);
                    }
                }

                // Faz o PATCH atualizando a licença existente no Supabase
                const updateLicense = await fetch(`${SUPABASE_URL}/rest/v1/licenses?license_key=eq.${licenseKey}`, {
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

                if (updateLicense.ok) {
                    return res.status(200).json({ status: 'approved', license_key: licenseKey, message: 'renovada' });
                }
            }
        }

        // 3. E-mail novo! Gerar chave determinística única baseada no ID do pagamento
        const payIdStr = String(id);
        const p1 = payIdStr.substring(0, 4).padEnd(4, 'X');
        const p2 = payIdStr.substring(4, 8).padEnd(4, 'Y');
        const p3 = (payIdStr.substring(8) + 'AB').substring(0, 4).padEnd(4, 'Z');
        const licenseKey = `MR-${p1}-${p2}-${p3}`.toUpperCase();

        // 4. Cria a licença ativa no Supabase associando o e-mail
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
            return res.status(200).json({ status: 'approved', license_key: licenseKey, message: 'criada' });
        } else {
            const errText = await createLicense.text();
            console.error('Erro ao criar licença:', errText);
            return res.status(500).json({ error: 'Erro ao cadastrar nova licença' });
        }

    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
