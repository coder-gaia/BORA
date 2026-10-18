-- Recorte aproximado de demonstração, sem representar um limite administrativo oficial.
-- As lojas e seus nomes são fictícios; os pontos ficam próximos a vias da região central.
INSERT INTO service_areas (id, name, polygon)
VALUES (
    '10000000-0000-4000-8000-000000000001',
    'Centro expandido de São Paulo (recorte aproximado de demonstração)',
    ST_GeogFromText('SRID=4326;POLYGON((-46.730 -23.625, -46.560 -23.625, -46.560 -23.500, -46.730 -23.500, -46.730 -23.625))')
);

INSERT INTO stores (id, name, service_area_id, location, simulated)
SELECT
    ('20000000-0000-4000-8000-' || lpad(seed.number::text, 12, '0'))::uuid,
    seed.name,
    '10000000-0000-4000-8000-000000000001'::uuid,
    ST_SetSRID(ST_MakePoint(seed.longitude, seed.latitude), 4326)::geography,
    true
FROM (VALUES
    (1, 'Cozinha Bora Paulista', -46.6559, -23.5614),
    (2, 'Café Bora Consolação', -46.6590, -23.5560),
    (3, 'Padaria Bora Augusta', -46.6530, -23.5548),
    (4, 'Marmitas Bora Bela Vista', -46.6456, -23.5596),
    (5, 'Cantina Bora Bixiga', -46.6434, -23.5566),
    (6, 'Mercado Bora Liberdade', -46.6355, -23.5582),
    (7, 'Lanches Bora Sé', -46.6333, -23.5504),
    (8, 'Bistrô Bora República', -46.6423, -23.5431),
    (9, 'Doces Bora Arouche', -46.6462, -23.5413),
    (10, 'Cozinha Bora Santa Cecília', -46.6546, -23.5380),
    (11, 'Café Bora Higienópolis', -46.6592, -23.5418),
    (12, 'Padaria Bora Pacaembu', -46.6658, -23.5440),
    (13, 'Marmitas Bora Perdizes', -46.6718, -23.5320),
    (14, 'Cantina Bora Pompeia', -46.6881, -23.5279),
    (15, 'Mercado Bora Barra Funda', -46.6661, -23.5252),
    (16, 'Lanches Bora Bom Retiro', -46.6375, -23.5271),
    (17, 'Bistrô Bora Luz', -46.6351, -23.5350),
    (18, 'Doces Bora Brás', -46.6160, -23.5453),
    (19, 'Cozinha Bora Pari', -46.6192, -23.5314),
    (20, 'Café Bora Mooca', -46.6001, -23.5558),
    (21, 'Padaria Bora Cambuci', -46.6205, -23.5667),
    (22, 'Marmitas Bora Aclimação', -46.6305, -23.5710),
    (23, 'Cantina Bora Vergueiro', -46.6395, -23.5745),
    (24, 'Mercado Bora Paraíso', -46.6451, -23.5751),
    (25, 'Lanches Bora Vila Mariana', -46.6380, -23.5894),
    (26, 'Bistrô Bora Ana Rosa', -46.6384, -23.5813),
    (27, 'Doces Bora Chácara Klabin', -46.6307, -23.5925),
    (28, 'Cozinha Bora Ipiranga', -46.6107, -23.5839),
    (29, 'Café Bora Pinheiros', -46.6846, -23.5668),
    (30, 'Padaria Bora Vila Madalena', -46.6924, -23.5576),
    (31, 'Marmitas Bora Fradique', -46.6840, -23.5653),
    (32, 'Cantina Bora Oscar Freire', -46.6717, -23.5600),
    (33, 'Mercado Bora Jardins', -46.6674, -23.5650),
    (34, 'Lanches Bora Itaim', -46.6755, -23.5840),
    (35, 'Bistrô Bora Vila Olímpia', -46.6840, -23.5945),
    (36, 'Doces Bora Moema', -46.6648, -23.6014),
    (37, 'Cozinha Bora Indianópolis', -46.6618, -23.6101),
    (38, 'Café Bora Saúde', -46.6351, -23.6104),
    (39, 'Padaria Bora Sumaré', -46.6776, -23.5503),
    (40, 'Marmitas Bora Lapa', -46.7049, -23.5210)
) AS seed(number, name, longitude, latitude);
