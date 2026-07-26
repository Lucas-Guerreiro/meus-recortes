// API Vercel Serverless Function: api/recuperar-chave.js
// Permite recuperar uma chave de licença ativa usando o e-mail de compra

export default async function handler(req, res) {
    const { email } = req.query;

    if (!email || !email.includes('@')) {
        return res.status(400).json({ error: 'E-mail inválido ou não informado' });
    }

    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Chaves de servidor do Supabase ausentes' });
    }

    try {
        // Busca a licença vinculada a esse e-mail no Supabase
        const response = await fetch(`${SUPABASE_URL}/rest/v1/licenses?email=eq.${encodeURIComponent(email.trim().toLowerCase())}&select=*`, {
            method: 'GET',
            headers: {
                'apikey': SUPABASE_SERVICE_ROLE_KEY,
                'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`
            }
        });

        if (!response.ok) {
            return res.status(500).json({ error: 'Erro ao consultar banco de dados' });
        }

        const data = await response.json();

        if (!data || data.length === 0) {
            return res.status(404).json({ error: 'Nenhuma licença ativa encontrada para este e-mail' });
        }

        const license = data[0];

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
