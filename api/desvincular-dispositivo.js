// API Vercel Serverless Function: api/desvincular-dispositivo.js
// Permite desvincular o device_id de uma licença (logout)

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { license_key, device_id } = req.body;

    if (!license_key) {
        return res.status(400).json({ error: 'Chave da licença é obrigatória' });
    }

    const cleanKey = license_key.trim().toUpperCase();
    const cleanDeviceId = device_id ? device_id.trim() : null;

    try {
        if (cleanDeviceId) {
            await sql`
                UPDATE licenses 
                SET device_id = NULL 
                WHERE license_key = ${cleanKey} AND device_id = ${cleanDeviceId}
            `;
        } else {
            await sql`
                UPDATE licenses 
                SET device_id = NULL 
                WHERE license_key = ${cleanKey}
            `;
        }

        return res.status(200).json({ success: true, message: 'Dispositivo desvinculado com sucesso!' });
    } catch (e) {
        console.error(e);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
