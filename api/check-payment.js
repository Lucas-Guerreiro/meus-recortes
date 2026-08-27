// API Vercel Serverless Function: api/check-payment.js
// Verifica o status do pagamento no Mercado Pago e ativa/renova a licença no Vercel Postgres

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    const { id } = req.query;

    if (!id) {
        return res.status(400).json({ error: 'ID do pagamento não informado' });
    }

    const MP_ACCESS_TOKEN = process.env.MERCADO_PAGO_ACCESS_TOKEN;

    if (!MP_ACCESS_TOKEN) {
        return res.status(500).json({ error: 'Token do Mercado Pago ausente' });
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
        const { rows } = await sql`
            SELECT * FROM licenses 
            WHERE email = ${customerEmail}
        `;

        if (rows.length > 0) {
            const existingLicense = rows[0];
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
                    novoActivatedAt = new Date();
                    novoActivatedAt.setDate(novoActivatedAt.getDate() + diasRestantes);
                }
            }

            const nowStr = novoActivatedAt.toISOString();

            // Atualiza a licença existente no Postgres
            await sql`
                UPDATE licenses 
                SET is_active = true, activated_at = ${nowStr} 
                WHERE license_key = ${licenseKey}
            `;

            return res.status(200).json({ status: 'approved', license_key: licenseKey, message: 'renovada' });
        }

        // 3. E-mail novo! Gerar chave determinística única baseada no ID do pagamento
        const payIdStr = String(id);
        const p1 = payIdStr.substring(0, 4).padEnd(4, 'X');
        const p2 = payIdStr.substring(4, 8).padEnd(4, 'Y');
        const p3 = (payIdStr.substring(8) + 'AB').substring(0, 4).padEnd(4, 'Z');
        const licenseKey = `MR-${p1}-${p2}-${p3}`.toUpperCase();

        // 4. Cria a licença ativa no Postgres associando o e-mail (com activated_at nulo até a validação do app)
        await sql`
            INSERT INTO licenses (license_key, is_active, device_id, activated_at, email) 
            VALUES (${licenseKey}, true, NULL, NULL, ${customerEmail})
        `;

        return res.status(200).json({ status: 'approved', license_key: licenseKey, message: 'criada' });

    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
