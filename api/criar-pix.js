// API Vercel Serverless Function: api/criar-pix.js
// Gera a cobrança Pix no Mercado Pago

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { email } = req.body;

    if (!email || !email.includes('@')) {
        return res.status(400).json({ error: 'E-mail inválido' });
    }

    const MP_ACCESS_TOKEN = process.env.MERCADO_PAGO_ACCESS_TOKEN;
    if (!MP_ACCESS_TOKEN) {
        return res.status(500).json({ error: 'Token do Mercado Pago não configurado no servidor' });
    }

    // Identificador único da transação no nosso sistema
    const transactionId = `rec_${Date.now()}`;

    try {
        // Chamada oficial à API do Mercado Pago para gerar pagamento Pix
        const mpResponse = await fetch('https://api.mercadopago.com/v1/payments', {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${MP_ACCESS_TOKEN}`,
                'Content-Type': 'application/json',
                'X-Idempotency-Key': transactionId
            },
            body: JSON.stringify({
                transaction_amount: 4.99,
                description: 'Assinatura Mensal - Meus Recortes',
                payment_method_id: 'pix',
                payer: {
                    email: email.trim(),
                    first_name: 'Cliente',
                    last_name: 'Meus Recortes'
                }
            })
        });

        if (!mpResponse.ok) {
            const errData = await mpResponse.text();
            console.error('Erro Mercado Pago:', errData);
            return res.status(500).json({ error: 'Erro ao gerar Pix no Mercado Pago' });
        }

        const payment = await mpResponse.json();

        // Extrai os dados do Pix necessários para o frontend
        const qrCode = payment.point_of_interaction.transaction_data.qr_code;
        const qrCodeBase64 = payment.point_of_interaction.transaction_data.qr_code_base64;
        const paymentId = payment.id;

        return res.status(200).json({
            payment_id: paymentId,
            qr_code: qrCode,
            qr_code_base64: qrCodeBase64
        });

    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: 'Erro interno do servidor' });
    }
}
