// API Vercel Serverless Function: api/recuperar-chave.js
// Permite recuperar uma chave de licença ativa usando o e-mail de compra

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    const { email } = req.query;

    if (!email || !email.includes('@')) {
        return res.status(400).json({ error: 'E-mail inválido ou não informado' });
    }

    try {
        // Busca a licença vinculada a esse e-mail no Postgres
        const { rows } = await sql`
            SELECT * FROM licenses 
            WHERE email = ${email.trim().toLowerCase()}
        `;

        if (rows.length === 0) {
            return res.status(404).json({ error: 'Nenhuma licença ativa encontrada para este e-mail' });
        }

        const license = rows[0];

        // Retorna a chave da licença encontrada
        return res.status(200).json({
            license_key: license.license_key,
            is_active: license.is_active,
            activated_at: license.activated_at
        });

    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
