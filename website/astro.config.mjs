// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';
import starlightLinksValidator from 'starlight-links-validator';

// https://astro.build/config
export default defineConfig({
	site: 'https://evsinev.github.io',
	base: '/dc-agent',
	integrations: [
		// Must run before Starlight so ```mermaid fences render as diagrams (not code blocks).
		mermaid({ theme: 'default', autoTheme: true }),
		starlight({
			title: 'dc-agent',
			description:
				'Utility HTTP agent for CI/CD: artifact upload, service deployment, config delivery',
			customCss: ['./src/styles/mermaid.css'],
			plugins: [starlightLinksValidator()],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/evsinev/dc-agent' },
			],
			sidebar: [
				{ label: 'Start here', items: ['index', 'installation', 'configuration'] },
				{ label: 'Commands', items: [{ autogenerate: { directory: 'commands' } }] },
				{ label: 'Tools', items: [{ autogenerate: { directory: 'tools' } }] },
				{ label: 'Reference', items: [{ autogenerate: { directory: 'reference' } }] },
				{ label: 'Internals', items: [{ autogenerate: { directory: 'internals' } }] },
			],
		}),
	],
});
