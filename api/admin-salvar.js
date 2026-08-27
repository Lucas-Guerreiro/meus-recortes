// API Vercel Serverless Function: api/admin-salvar.js
// Cria, atualiza ou deleta uma licença no banco de dados Vercel Postgres

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "admin123";
    const authHeader = req.headers.authorization;

    if (!authHeader || authHeader !== `Bearer ${ADMIN_PASSWORD}`) {
        return res.status(401).json({ error: 'Não autorizado. Senha inválida.' });
    }

    const { license_key, email, is_active, device_id, activated_at, action } = req.body;

    if (!license_key) {
        return res.status(400).json({ error: 'Chave da licença é obrigatória' });
    }

    const cleanEmail = email ? email.trim().toLowerCase() : null;
    const cleanKey = license_key.trim().toUpperCase();

    try {
        if (action === 'delete') {
            // Deletar licença
            await sql`
                DELETE FROM licenses 
                WHERE license_key = ${cleanKey}
            `;
            return res.status(200).json({ success: true, message: 'Licença deletada com sucesso' });
        }

        // Upsert (criar ou atualizar) no Postgres
        const isActiveBool = is_active === true;
        const finalDeviceId = device_id === undefined ? null : device_id;
        const finalActivatedAt = activated_at || null;

        await sql`
            INSERT INTO licenses (license_key, email, is_active, device_id, activated_at)
            VALUES (${cleanKey}, ${cleanEmail}, ${isActiveBool}, ${finalDeviceId}, ${finalActivatedAt})
            ON CONFLICT (license_key) 
            DO UPDATE SET 
                email = EXCLUDED.email,
                is_active = EXCLUDED.is_active,
                device_id = EXCLUDED.device_id,
                activated_at = EXCLUDED.activated_at
        `;

        return res.status(200).json({ success: true, message: 'Licença salva com sucesso!' });
    } catch (e) {
        console.error(e);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
