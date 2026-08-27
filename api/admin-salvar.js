// API Vercel Serverless Function: api/admin-salvar.js
// Cria, atualiza ou deleta uma licença no banco de dados

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

    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Configurações do Supabase ausentes' });
    }

    const cleanEmail = email ? email.trim().toLowerCase() : null;
    const cleanKey = license_key.trim().toUpperCase();

    try {
        if (action === 'delete') {
            // Deletar licença
            const response = await fetch(`${SUPABASE_URL}/rest/v1/licenses?license_key=eq.${encodeURIComponent(cleanKey)}`, {
                method: 'DELETE',
                headers: {
                    'apikey': SUPABASE_SERVICE_ROLE_KEY,
                    'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`
                }
            });

            if (!response.ok) {
                return res.status(500).json({ error: 'Erro ao deletar licença no Supabase' });
            }

            return res.status(200).json({ success: true, message: 'Licença deletada com sucesso' });
        }

        // Upsert (criar ou atualizar)
        const response = await fetch(`${SUPABASE_URL}/rest/v1/licenses`, {
            method: 'POST',
            headers: {
                'apikey': SUPABASE_SERVICE_ROLE_KEY,
                'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
                'Content-Type': 'application/json',
                'Prefer': 'resolution=merge-duplicates'
            },
            body: JSON.stringify({
                license_key: cleanKey,
                email: cleanEmail,
                is_active: is_active === true,
                device_id: device_id === undefined ? null : device_id,
                activated_at: activated_at || null
            })
        });

        if (!response.ok) {
            const errorText = await response.text();
            console.error("Erro no upsert:", errorText);
            return res.status(500).json({ error: 'Erro ao salvar licença no banco de dados' });
        }

        return res.status(200).json({ success: true, message: 'Licença salva com sucesso!' });
    } catch (e) {
        console.error(e);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}
