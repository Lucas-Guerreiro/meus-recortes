// API Vercel Serverless Function: api/admin-listar.js
// Lista todas as licenças do banco de dados Vercel Postgres

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "admin123";
    const authHeader = req.headers.authorization;

    if (!authHeader || authHeader !== `Bearer ${ADMIN_PASSWORD}`) {
        return res.status(401).json({ error: 'Não autorizado. Senha inválida.' });
    }

    try {
        const { rows } = await sql`
            SELECT * FROM licenses 
            ORDER BY email ASC, license_key ASC
        `;

        return res.status(200).json(rows);
    } catch (e) {
        console.error(e);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
