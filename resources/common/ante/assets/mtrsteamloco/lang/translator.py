import requests
import threading
import json
import os
from openai import OpenAI

# 读取 API 密钥
with open("D://OpenRouter.json", "r", encoding="utf-8") as f:
    api_keys = json.load(f)
    api_key = api_keys["api_key"]

print("API key: " + api_key)

# 读取 zhcn.json 文件
with open("zh_cn.json", "r", encoding="utf-8") as f:
    content = json.dumps(json.load(f), ensure_ascii=False, indent=4)
    content = content.replace("\\n", "<br>")

# raise Exception("Stop here")


# 构造 client
client = OpenAI(
    api_key=api_key, 
    base_url="https://openrouter.ai/api/v1/", 
)

# 目标语言
languages = ["en_us", "af_za", "ar_sa", "ast_es", "az_az", "ba_ru", "bar", "be_by", "be_latn", "bg_bg", "br_fr", "brb", "bs_ba", "ca_es", "cs_cz", "cy_gb", "da_dk", "de_at", "de_ch", "de_de", "el_gr", "en_au", "en_ca", "en_gb", "en_nz", "en_pt", "en_ud", "enp", "enws", "eo_uy", "es_ar", "es_cl", "es_ec", "es_es", "es_mx", "es_uy", "es_ve", "esan", "et_ee", "eu_es", "fa_ir", "fi_fi", "fil_ph", "fo_fo", "fr_ca", "fr_fr", "fra_de", "fur_it", "fy_nl", "ga_ie", "gd_gb", "gl_es", "haw_us", "he_il", "hi_in", "hn_no", "hr_hr", "hu_hu", "hy_am", "id_id", "ig_ng", "io_en", "is_is", "isv", "it_it", "ja_jp", "jbo_en", "ka_ge", "kk_kz", "kn_in", "ko_kr", "ksh", "kw_gb", "ky_kg", "la_la", "lb_lu", "li_li", "lmo", "lo_la", "lol_us", "lt_lt", "lv_lv", "lzh", "mk_mk", "mn_mn", "ms_my", "mt_mt", "nah", "nds_de", "nl_be", "nl_nl", "nn_no", "no_no", "oc_fr", "ovd", "pl_pl", "pls", "pt_br", "pt_pt", "qya_aa", "ro_ro", "rpr", "ru_ru", "ry_ua", "sah_sah", "se_no", "sk_sk", "sl_si", "so_so", "sq_al", "sr_cs", "sr_sp", "sv_se", "sxu", "szl", "ta_in", "th_th", "tl_ph", "tlh_aa", "tok", "tr_tr", "tt_ru", "tzo_mx", "uk_ua", "val_es", "vec_it", "vi_vn", "vp_vl", "yi_de", "yo_ng", "zh_hk", "zh_tw", "zlm_arab"]

target_languages = ["en_us", "de_de", "ja_jp", "is_is", "pt_pt", "ko_kr", "ru_ru", "zh_hk", "zh_tw"]

def translate(target):

    text = text = f"""请将以下JSON语言文件准确翻译为{target}语言,
    保留源文件格式,输出纯净的JSON文本,保留如 ANTE BBMODEL <br> 网址或人名不变.
    不需要```json 的标签，请直接输出 JSON 文本。
     严格按此格式输出翻译结果：\n{content}"""

    completion = client.chat.completions.create(
        model="microsoft/mai-ds-r1:free",
        messages=[
            {
                "role": "user",
                "content": text
            }
        ]
    )

    result = completion.choices[0].message.content
    result = result.replace("<br>", "\\n")

    # 写入文件
    with open(target + ".json", "w", encoding="utf-8") as f:
        f.write(result)

    print(f"Translation to {target} done.")

for target in languages:
    if target == "zh_cn":
        continue
    file_path = target + ".json"
    if os.path.exists(file_path):
        os.remove(file_path)

threads = []
for target in target_languages:
    if target == "zh_cn":
        continue
    threads.append(threading.Thread(target=translate, args=(target,)))

for thread in threads:
    thread.start()

for thread in threads:
    thread.join()

print("All translations done.")