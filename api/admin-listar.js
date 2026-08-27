// API Vercel Serverless Function: api/admin-listar.js
// Lista todas as licenças do banco de dados

export default async function handler(req, res) {
    const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "admin123";
    const authHeader = req.headers.authorization;

    if (!authHeader || authHeader !== `Bearer ${ADMIN_PASSWORD}`) {
        return res.status(401).json({ error: 'Não autorizado. Senha inválida.' });
    }

    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Configurações do Supabase ausentes' });
    }

    try {
        const response = await fetch(`${SUPABASE_URL}/rest/v1/licenses?select=*&order=email.asc`, {
            method: 'GET',
            headers: {
                'apikey': SUPABASE_SERVICE_ROLE_KEY,
                'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`
            }
        });

        if (!response.ok) {
            throw new Error('Erro ao consultar banco de dados');
        }

        const data = await response.json();
        return res.status(200).json(data);
    } catch (e) {
        console.error(e);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
