// API Vercel Serverless Function: api/criar-teste.js
// Cria ou renova uma licença de teste gratuita de 3 dias de forma segura usando Vercel Postgres

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { email } = req.body;

    if (!email || !email.includes('@')) {
        return res.status(400).json({ error: 'E-mail inválido ou não informado' });
    }

    const cleanEmail = email.trim().toLowerCase();

    function generateRandomKey() {
        const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
        let part1 = '';
        let part2 = '';
        let part3 = '';
        for (let i = 0; i < 4; i++) {
            part1 += chars.charAt(Math.floor(Math.random() * chars.length));
            part2 += chars.charAt(Math.floor(Math.random() * chars.length));
            part3 += chars.charAt(Math.floor(Math.random() * chars.length));
        }
        return `TEST-${part1}-${part2}-${part3}`;
    }

    try {
        // 1. Verifica se já existe uma licença para este e-mail
        const { rows } = await sql`
            SELECT * FROM licenses 
            WHERE email = ${cleanEmail}
        `;

        const now = new Date().toISOString();

        if (rows.length > 0) {
            const existingLicense = rows[0];
            const licenseKey = existingLicense.license_key;

            // Atualiza a licença existente reativando e redefinindo a data de teste no activated_at e limpando o device_id
            await sql`
                UPDATE licenses 
                SET is_active = true, activated_at = ${now}, device_id = NULL 
                WHERE license_key = ${licenseKey}
            `;

            return res.status(200).json({
                success: true,
                license_key: licenseKey,
                message: "Licença de teste renovada com sucesso!"
            });
        }

        // 2. Não existe licença. Cria uma nova licença TEST-
        const newLicenseKey = generateRandomKey();

        await sql`
            INSERT INTO licenses (license_key, is_active, email, activated_at, device_id) 
            VALUES (${newLicenseKey}, true, ${cleanEmail}, ${now}, NULL)
        `;

        return res.status(200).json({
            success: true,
            license_key: newLicenseKey,
            message: "Nova licença de teste gerada com sucesso!"
        });

    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: 'Erro interno ao processar requisição' });
    }
}
