import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import {resolve,join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import JSZip from 'jszip';
import sharp from 'sharp';
import {creativeTargets} from './export-targets.mjs';
import {validateCreativeBundle} from './creative-bundle.mjs';
const here=dirname(fileURLToPath(import.meta.url));
const out=resolve(here,'../../../artifacts/app-store-new');
const project=JSON.parse(readFileSync(join(here,'app-store-screenshots.json'),'utf8'));
const destination=join(out,'creative-assets');
// Validate every archive before replacing any published output.
const assets=[];
for(const target of creativeTargets){
 assets.push(...await validateCreativeBundle(readFileSync(join(out,`${target.key}.zip`)),target,project.locales,project.slidesByDevice[target.key]?.length||0));
}
const zip=new JSZip();
for(const asset of assets){
 const file=join(destination,asset.file);mkdirSync(dirname(file),{recursive:true});writeFileSync(file,asset.data);
 zip.file(asset.file,asset.data);
}
const manifest=assets.map(({data,...asset})=>asset);
writeFileSync(join(destination,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
zip.file('manifest.json',JSON.stringify(manifest,null,2));
writeFileSync(join(out,'alpha-gps-creative-assets.zip'),await zip.generateAsync({type:'nodebuffer'}));
const tiles=[];const cards=[];
for(const [column,target] of creativeTargets.entries())for(const [row,locale]of project.locales.entries()){
 const asset=assets.find(a=>a.device===target.key&&a.locale===locale&&a.width===target.sizes[0][0]);
 tiles.push({input:await sharp(asset.data).resize(600,400,{fit:'contain',background:'#eeeaf5'}).toBuffer(),left:column*600,top:row*400});
 cards.push(`<section><h2>${target.label} · ${locale} · ${asset.width}×${asset.height}</h2><a href="${asset.file}"><img src="${asset.file}" alt="${target.label}, ${locale}"></a></section>`);
}
await sharp({create:{width:1800,height:400*project.locales.length,channels:3,background:'#eeeaf5'}}).composite(tiles).png().toFile(join(out,'creative-preview.png'));
writeFileSync(join(destination,'index.html'),`<!doctype html><html lang="en"><meta charset="utf-8"><title>Alpha GPS creative assets</title><style>body{max-width:1200px;margin:40px auto;font:16px system-ui;background:#f3f0fa;color:#191426}img{width:100%;display:block}section{margin:40px 0}</style><h1>Alpha GPS · Creative assets</h1><p>Static product-page headers, search-results artwork and universal images. Confirm placement crops using App Store Connect’s Preview tool.</p><a href="../alpha-gps-creative-assets.zip">Download all creative assets</a>${cards.join('')}</html>`);
console.log(`Verified ${assets.length} opaque sRGB creative PNGs; saved alpha-gps-creative-assets.zip`);
