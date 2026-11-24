#!/usr/bin/env python3
"""白い熊 地図: the item colour roles (black-yellow skill, “Colours”, the MIXED way).

One table, below: every item of OsmAnd's UI that has a colour of its own, the section of the UI page it
is set in, the rule its default follows, and its label in English and Japanese. A row names an OsmAnd
colour: a bare name stands for the pair ‹name›_dark / ‹name›_light, which one role feeds alike (白い熊,
2026-11-24: always black-yellow, whichever theme is on); names after “=” are fed exactly as written.

Writes, from the repository root:
  OsmAnd/src/net/osmand/plus/chizu/ChizuRoleTable.java
  OsmAnd/res/values/chizu_role_strings.xml
  OsmAnd/res/values-ja/chizu_role_strings.xml

Rules (the default, derived in code from the foundation colours of the UI page):
  G ground   T text   D dim text   A accent   I icon   L line   O content on an accent fill
  Mnn  nn % of the way from the ground to the accent, opaque
  Ann  the accent at nn % opacity      Gnn  the ground at nn % opacity      Tnn  the text at nn % opacity
  K    keeps upstream's dark-theme value: a colour that carries meaning
"""
import re, sys

SECTIONS = ["text", "buttons", "controls", "lists", "bars", "menus", "mapw", "nav", "charts", "keys", "msgs",
            "scheme", "extras"]

T = r"""
# ---- text and icons
text | text_color_tab_active | T | Active tab: text | 選択中のタブ: 文字
text | icon_color_inverse | T | Icon on a coloured ground | 色付きの地の上のアイコン
text | icon_color_osmand | A | Brand-coloured icon | ブランド色のアイコン
text | icon_color_secondary | D | Secondary icon | 補助のアイコン
text | searchbar_tab_inactive | T60 | Search bar: inactive tab | 検索バー: 選択していないタブ
text | widget_units_color | D | Units beside a value | 値に添える単位
text | preference_title = preference_category_title | A | Settings: group title | 設定: グループの見出し
text | preference_action = preference_action_button_text | A | Settings: action text | 設定: 操作の文字
text | grid_menu_icon = grid_menu_icon_dark | I | Grid menu: icon | グリッドメニュー: アイコン
# ---- buttons
buttons | active_buttons_and_links_bg_pressed | M30 | Active button, pressed: ground | 有効なボタン（押下中）: 地
buttons | active_buttons_and_links_trans | A40 | Active button: see-through ground | 有効なボタン: 透ける地
buttons | active_color_secondary | M20 | Selected item: ground | 選択中の項目: 地
buttons | inactive_buttons_and_links_bg | M12 | Inactive button: ground | 無効なボタン: 地
buttons | stroked_buttons_and_links_bg | G00 | Outlined button: ground | 枠付きボタン: 地
buttons | btn_bg_primary | M25 | Main button: ground | 主ボタン: 地
buttons | btn_bg_primary_pressed | M40 | Main button, pressed: ground | 主ボタン（押下中）: 地
buttons | btn_text_on_primary | T | Main button: text | 主ボタン: 文字
buttons | btn_outline_primary_focused | A | Main button, focused: frame | 主ボタン（フォーカス中）: 枠
buttons | btn_bg_secondary | G | Second button: ground | 副ボタン: 地
buttons | btn_bg_secondary_pressed | M20 | Second button, pressed: ground | 副ボタン（押下中）: 地
buttons | btn_text_on_secondary | A | Second button: text | 副ボタン: 文字
buttons | btn_icon_on_secondary | A | Second button: icon | 副ボタン: アイコン
buttons | btn_outline_secondary | L | Second button: frame | 副ボタン: 枠
buttons | btn_bg_accent | M20 | Accent button: ground | アクセントのボタン: 地
buttons | btn_bg_accent_pressed | M35 | Accent button, pressed: ground | アクセントのボタン（押下中）: 地
buttons | btn_outline_accent | A | Accent button: frame | アクセントのボタン: 枠
buttons | btn_bg_disabled | G | Disabled button: ground | 使えないボタン: 地
buttons | btn_text_on_disabled | A38 | Disabled button: text | 使えないボタン: 文字
buttons | btn_bg_icon_focused | M20 | Icon button, focused: ground | アイコンボタン（フォーカス中）: 地
buttons | btn_bg_icon_hover | M12 | Icon button, pointed at: ground | アイコンボタン（ポインターが上）: 地
buttons | btn_bg_icon_pressed | M30 | Icon button, pressed: ground | アイコンボタン（押下中）: 地
buttons | fab = fab_color | M25 | Floating button: ground | フローティングボタン: 地
buttons | fab_pressed = fab_color_pressed | M40 | Floating button, pressed: ground | フローティングボタン（押下中）: 地
buttons | profile_button = profile_button_gray | M12 | Profile button: ground | プロファイルのボタン: 地
buttons | profile_button_pressed = profile_button_gray_pressed | M25 | Profile button, pressed: ground | プロファイルのボタン（押下中）: 地
buttons | button_active_light = button_color_active_light | A | Active button of the light theme | ライトテーマの有効なボタン
# ---- switches, chips and fields
controls | chip_bg | G | Chip: ground | チップ: 地
controls | chip_bg_selected | M30 | Selected chip: ground | 選択中のチップ: 地
controls | chip_search_scope_bg | M12 | Search scope chip: ground | 検索範囲のチップ: 地
controls | material_switch_outline | M55 | Switch: outline | スイッチ: 輪郭
controls | switch_button_active | A10 | Switch button, on: ground | 切り替えボタン（オン）: 地
controls | switch_button_active_stroke | A50 | Switch button, on: frame | 切り替えボタン（オン）: 枠
controls | switch_top_off = preference_top_switch_off | M55 | Top switch, off | 上部のスイッチ（オフ）
controls | toolbar_switch_track = map_toolbar_switch_track_color | M40 | Toolbar switch: track | ツールバーのスイッチ: 溝
controls | slider_label_text | T | Slider: value label | スライダー: 値のラベル
controls | text_field_box | M20 | Text field: box | 入力欄: 箱
controls | text_input_background | T10 | Text field: ground | 入力欄: 地
controls | input_layout_bg = input_layout_bg_color material_text_input_layout_bg | T10 | Text field, filled: ground | 入力欄（塗り）: 地
controls | input_box_stroke = mtrl_textinput_default_box_stroke_color | L | Text field: frame | 入力欄: 枠
controls | inactive_pages | M40 | Page dots, inactive | ページの点（選択していない）
controls | download_pressed | A30 | Download row, pressed | ダウンロードの行（押下中）
controls | map_progress_bar_bg | M20 | Progress bar: track | 進行バー: 溝
# ---- lists and cards
lists | activity_background_transparent_color | G92 | Screen ground, see-through | 画面の地（透過）
lists | spinner_list_background | G | Drop-down list: ground | ドロップダウンのリスト: 地
lists | list_item_pressed = list_item_light_pressed list_item_dark_pressed | A20 | List row, pressed | リストの行（押下中）
lists | group_background = group_background | M20 | Group header: ground | グループの見出し: 地
lists | tab_pressed = background_tab_pressed | A10 | Tab, pressed | タブ（押下中）
lists | list_divider = list_divider_dark divider_color | L | List divider, older screens | リストの区切り線（古い画面）
lists | empty_hint_bg = empty_hint_bg | G50 | Empty-list hint: ground | 空のリストの案内: 地
# ---- bars
bars | active_buttons_and_links_text | T | Bar: text and icons | バー: 文字とアイコン
bars | app_bar_active | M25 | Top bar in selection mode | 選択モードの上部バー
bars | app_bar_secondary | G | Second top bar | 第二の上部バー
bars | tool_bar_color | G80 | Toolbar over the map | 地図の上のツールバー
bars | tool_bar_light = tool_bar_orange_color_light | G80 | Toolbar over the map, light theme | 地図の上のツールバー（ライトテーマ）
bars | status_bar_main | G | Status bar | ステータスバー
bars | status_bar_dim | G | Status bar, dimmed | ステータスバー（暗く）
bars | status_bar_secondary | G | Status bar of second screens | 第二画面のステータスバー
bars | status_bar_selection_color | M25 | Status bar in selection mode | 選択モードのステータスバー
bars | navigation_bar_bg | G | Navigation bar | ナビゲーションバー
# ---- menus and sheets
menus | ctx_menu_buttons_bg | G | Place sheet: button row ground | 地点シート: ボタン行の地
menus | ctx_menu_bottom_buttons_text_color | A | Place sheet: button text | 地点シート: ボタンの文字
menus | ctx_menu_bottom_view_secondary_text_color | D | Place sheet: secondary text | 地点シート: 補助の文字
menus | ctx_menu_controller_disabled_text_color | A38 | Place sheet: disabled text | 地点シート: 使えない文字
menus | ctx_button_text = ctx_menu_controller_button_text_color_light_n ctx_menu_controller_button_text_color_dark_n | A | Place sheet button: text | 地点シートのボタン: 文字
menus | ctx_button_text_pressed = ctx_menu_controller_button_text_color_light_p ctx_menu_controller_button_text_color_dark_p | T | Place sheet button, pressed: text | 地点シートのボタン（押下中）: 文字
menus | ctx_button_outline = ctx_menu_controller_button_outline_color_light_n ctx_menu_controller_button_outline_color_dark_n | L | Place sheet button: frame | 地点シートのボタン: 枠
menus | ctx_button_outline_pressed = ctx_menu_controller_button_outline_color_light_p ctx_menu_controller_button_outline_color_dark_p | A | Place sheet button, pressed: frame | 地点シートのボタン（押下中）: 枠
menus | ctx_button_bg_pressed = ctx_menu_controller_button_bg_color_light_p ctx_menu_controller_button_bg_color_dark_p | M30 | Place sheet button, pressed: ground | 地点シートのボタン（押下中）: 地
menus | multi_selection_menu_close_btn | A | Selection menu: close button | 選択メニュー: 閉じるボタン
menus | popup_bg = popup_bg_color | G93 | Popup: ground | ポップアップ: 地
menus | popup_text = popup_text_color | T | Popup: text | ポップアップ: 文字
menus | popup_separator = popup_separator_color | L | Popup: divider | ポップアップ: 区切り線
menus | direction_selected = show_direction_menu_selected_item_bg | M20 | Direction menu: selected row | 方向メニュー: 選択中の行
# ---- map widgets
mapw | map_background_color | G | Ground behind the map | 地図の後ろの地
mapw | map_widget | G63 | Widget over the map: ground | 地図の上のウィジェット: 地
mapw | widget_background_color | G | Widget panel: ground | ウィジェットのパネル: 地
mapw | widget_secondary_background_color | M12 | Widget panel: second ground | ウィジェットのパネル: 第二の地
mapw | widget_text = widgettext_night widgettext_day | T | Widget: text | ウィジェット: 文字
mapw | widget_text_shadow = widgettext_shadow_night widgettext_shadow_day | G | Widget: text outline | ウィジェット: 文字の縁取り
mapw | widget_icon = map_widget_icon_color | A | Widget: icon | ウィジェット: アイコン
mapw | nav_arrow_circle_color | D | Turn arrow: circle | 曲がる矢印: 円
mapw | marker_circle = marker_circle_button_color_dark | M25 | Marker button: ground | マーカーのボタン: 地
mapw | speedometer_limit_units_color | K | Speed limit sign: units | 制限速度の標識: 単位
mapw | speedometer_tolerance_units_color | K | Speed tolerance sign: units | 許容速度の標識: 単位
# ---- navigation
nav | route_info_go_btn_bg | M30 | Start button: ground | 開始ボタン: 地
nav | route_go_pressed = route_info_go_btn_bg_light_p route_info_go_btn_bg_dark_p | M45 | Start button, pressed: ground | 開始ボタン（押下中）: 地
nav | route_info_go_btn_inking | A | Start button: ink | 開始ボタン: 色
nav | route_info_cancel_button_color | G | Cancel button: ground | キャンセルボタン: 地
nav | routing_standard_progress_fill | A | Route search: progress | 経路探索: 進み
nav | routing_standard_progress_track | M25 | Route search: track | 経路探索: 溝
nav | routing_fast_progress_fill | K | Fast route search: progress | 高速な経路探索: 進み
nav | routing_fast_progress_track | K | Fast route search: track | 高速な経路探索: 溝
# ---- charts
charts | chart_axis_grid_line | M30 | Chart: grid line | グラフ: 目盛り線
charts | chart_axis_x_grid_line | M40 | Chart: vertical grid line | グラフ: 縦の目盛り線
charts | chart_axis_x_text | D | Chart: axis text | グラフ: 軸の文字
charts | gpx_chart_grid | A16 | Track chart: grid | 軌跡のグラフ: 目盛り
# ---- keyboard and coordinate input
keys | keyboard_item_text_color | T | Key: text | キー: 文字
keys | keyboard_item_add_button_divider | L | Add key: divider | 追加キー: 区切り線
keys | key_text_pressed = keyboard_item_text_color_light_pressed keyboard_item_button_text_color | T | Key, pressed: text | キー（押下中）: 文字
keys | key_divider_pressed = keyboard_item_divider_control_color_light_pressed | L | Key, pressed: divider | キー（押下中）: 区切り線
keys | key_pressed_bg = keyboard_item_pressed_bg | M30 | Key, pressed: ground | キー（押下中）: 地
keys | key_add_bg = keyboard_item_add_button_light_bg keyboard_item_add_button_dark_bg | M20 | Add key: ground | 追加キー: 地
keys | key_add_bg_pressed = keyboard_item_add_button_light_bg_pressed keyboard_item_add_button_dark_bg_pressed | M35 | Add key, pressed: ground | 追加キー（押下中）: 地
keys | coordinate_icon = coordinate_input_active_icon_dark | I | Coordinate entry: active icon | 座標入力: 有効なアイコン
keys | coordinate_text_active = coordinate_input_edit_text_activated | A | Coordinate entry: active field | 座標入力: 入力中の欄
keys | coordinate_text_normal = coordinate_input_edit_text_normal_light | L | Coordinate entry: idle field | 座標入力: 入力していない欄
# ---- messages
msgs | error | K | Error | エラー
msgs | error_container | K | Error: container | エラー: 入れ物
msgs | error_icon | K | Error: icon | エラー: アイコン
msgs | on_error | K | Content on an error fill | エラーの塗りの上の内容
msgs | on_error_container | K | Content in an error container | エラーの入れ物の中の内容
msgs | success | K | Success | 成功
msgs | success_container | K | Success: container | 成功: 入れ物
msgs | success_icon | K | Success: icon | 成功: アイコン
msgs | on_success | K | Content on a success fill | 成功の塗りの上の内容
msgs | on_success_container | K | Content in a success container | 成功の入れ物の中の内容
msgs | warning | K | Warning | 警告
msgs | warning_container | K | Warning: container | 警告: 入れ物
msgs | warning_icon | K | Warning: icon | 警告: アイコン
msgs | on_warning | K | Content on a warning fill | 警告の塗りの上の内容
msgs | on_warning_container | K | Content in a warning container | 警告の入れ物の中の内容
msgs | text_negative = text_color_negative | K | Negative value: text | 負の値: 文字
msgs | text_positive = text_color_positive | K | Positive value: text | 正の値: 文字
msgs | icon_warning = icon_color_warning | K | Warning icon, older screens | 警告のアイコン（古い画面）
msgs | deletion_warning = deletion_color_warning | K | Deletion warning | 削除の警告
msgs | sale_badge | K | Sale badge | セールのバッジ
# ---- the Material scheme of the newer screens
scheme | primary | A | Primary | プライマリ
scheme | primary_pressed | M80 | Primary, pressed | プライマリ（押下中）
scheme | primary_inverse | A | Primary, inverse | プライマリ（反転）
scheme | on_primary | O | Content on primary | プライマリの上の内容
scheme | primary_container | M25 | Primary container | プライマリの入れ物
scheme | primary_container_pressed | M35 | Primary container, pressed | プライマリの入れ物（押下中）
scheme | on_primary_container | T | Content in a primary container | プライマリの入れ物の中の内容
scheme | secondary | D | Secondary | セカンダリ
scheme | on_secondary | O | Content on secondary | セカンダリの上の内容
scheme | secondary_container | M20 | Secondary container | セカンダリの入れ物
scheme | secondary_container_pressed | M30 | Secondary container, pressed | セカンダリの入れ物（押下中）
scheme | on_secondary_container | T | Content in a secondary container | セカンダリの入れ物の中の内容
scheme | surface | G | Surface | サーフェス
scheme | surface_container | G | Surface container | サーフェスの入れ物
scheme | surface_container_highest | M12 | Highest surface container | 最上位のサーフェスの入れ物
scheme | surface_container_highest_pressed | M20 | Highest surface container, pressed | 最上位のサーフェスの入れ物（押下中）
scheme | surface_inverse | T | Surface, inverse | サーフェス（反転）
scheme | on_surface | T | Content on a surface | サーフェスの上の内容
scheme | on_surface_variant | D | Secondary content on a surface | サーフェスの上の補助の内容
scheme | on_surface_disabled | A38 | Disabled content on a surface | サーフェスの上の使えない内容
scheme | on_surface_inverse | G | Content on an inverse surface | 反転サーフェスの上の内容
scheme | outline | L | Outline | 輪郭
scheme | outline_variant | M55 | Outline, quieter | 輪郭（控えめ）
scheme | outline_disabled | A38 | Outline, disabled | 輪郭（使えない）
scheme | v2_text = text_color_primary_light_v2 text_color_primary_dark_v2 | T | Text, version 2 | 文字（バージョン2）
scheme | v2_icon = icon_color_default_light_v2 icon_color_default_dark_v2 | I | Icon, version 2 | アイコン（バージョン2）
scheme | v2_icon_active = icon_color_active_light_v2 icon_color_active_dark_v2 | A | Active icon, version 2 | 有効なアイコン（バージョン2）
scheme | v2_active = active_color_primary_light_v2 active_color_primary_dark_v2 | A | Active colour, version 2 | 有効な色（バージョン2）
scheme | v2_active_secondary = active_color_secondary_light_v2 active_color_secondary_dark_v2 | M20 | Selected ground, version 2 | 選択中の地（バージョン2）
scheme | v2_buttons_secondary = buttons_secondary_light_v2 buttons_secondary_dark_v2 | M12 | Second button ground, version 2 | 副ボタンの地（バージョン2）
# ---- plugins and purchases
extras | brand_accent | K | Brand accent | ブランドのアクセント
extras | osmand_orange = osmand_orange osmand_orange_dark | K | OsmAnd orange | OsmAndのオレンジ
extras | plugin_details_install_header_bg | G | Plugin page: header ground | プラグインのページ: 見出しの地
extras | banner_feature_bg | G | Feature banner: ground | 機能の案内: 地
extras | banner_downloads_remaining | A | Downloads left: bar | 残りのダウンロード: バー
extras | banner_downloads_used | M25 | Downloads used: bar | 使ったダウンロード: バー
extras | purchase_sc_feature_list_bg | G | Purchase screen: feature list ground | 購入画面: 機能一覧の地
extras | purchase_sc_header_icon_bg | M12 | Purchase screen: header icon ground | 購入画面: 見出しアイコンの地
extras | purchase_sc_selected_plan_bg | G | Purchase screen: chosen plan ground | 購入画面: 選んだプランの地
extras | purchase_sc_toolbar_active | A | Purchase screen: active toolbar | 購入画面: 有効なツールバー
extras | subscription_active_bg_color | M20 | Active subscription: ground | 有効な定期購入: 地
extras | subscription_active_div_color | L | Active subscription: divider | 有効な定期購入: 区切り線
extras | maps_plus_badge_text | K | Maps+ badge: text | Maps+ のバッジ: 文字
extras | wikivoyage_welcome_bg | G | Travel guides: welcome ground | 旅行ガイド: 最初の画面の地
extras | wikivoyage_bottom_bar_bg | G | Travel guides: bottom bar | 旅行ガイド: 下部バー
extras | wikivoyage_secondary_btn_bg | M12 | Travel guides: second button ground | 旅行ガイド: 副ボタンの地
extras | inactive_item_orange = inactive_item_orange | A50 | Inactive brand-coloured item | 無効なブランド色の項目
"""

# The colours upstream wrote as literals into its layouts and shape files. Each row moves its literals to
# a colour resource “chizu_lit_‹key›”, which keeps upstream's value as the XML default (in the default
# values/ only — a qualified copy would beat the loader) and is fed from a role like every other.
#   key | section | rule | upstream's value | English | Japanese | file:literal …
LIT = r"""
switch_on | controls | M30 | #ff8f00 | Segmented switch, chosen: ground | 切り替え（選択中）: 地 | drawable/switch_ex_background_dark.xml:#ff8f00 drawable/switch_ex_background_light.xml:#ff8f00
switch_on_pressed | controls | M45 | #ffb24c | Segmented switch, pressed: ground | 切り替え（押下中）: 地 | drawable/switch_ex_background_dark.xml:#ffb24c drawable/switch_ex_background_light.xml:#ffb24c
switch_on_soft | controls | A40 | #66ff8f00 | Segmented switch: soft frame | 切り替え: 淡い枠 | drawable/switch_ex_background_dark.xml:#66ff8f00 drawable/switch_ex_background_light.xml:#66ff8f00
switch_off_frame | controls | M55 | #77848c | Segmented switch, not chosen: frame | 切り替え（選択していない）: 枠 | drawable/switch_ex_background_dark.xml:#77848c drawable/switch_ex_background_light.xml:#727272
switch_text_off | controls | D | #77848c | Segmented switch, not chosen: text | 切り替え（選択していない）: 文字 | color/switch_ex_button_text_dark.xml:#77848c color/switch_ex_button_text_light.xml:#727272
switch_text_on | controls | T | #ffffff | Segmented switch, chosen: text | 切り替え（選択中）: 文字 | color/switch_ex_button_text_dark.xml:#ffffff color/switch_ex_button_text_light.xml:#ffffff
size_progress | controls | M40 | #475259 | Storage bar | 容量のバー | drawable/size_progressbar_dark.xml:#475259 drawable/size_progressbar_light.xml:#475259
circle_outline | buttons | A16 | #28727272 | Round button: ring | 丸いボタン: 輪 | drawable/circle_outline_background_normal.xml:#28727272
plugin_logo_disabled | extras | M25 | #525e66 | Disabled plugin: logo ground | 無効なプラグイン: ロゴの地 | drawable/bg_plugin_logo_disabled_dark.xml:#525e66 drawable/bg_plugin_logo_disabled_light.xml:#cccccc
circle_soft | buttons | M20 | #E8F0FE | Round icon ground | 丸いアイコンの地 | drawable/bg_circle_light_blue.xml:#E8F0FE
circle_soft_ring | buttons | A16 | #204285F4 | Round icon ground: ring | 丸いアイコンの地: 輪 | drawable/bg_circle_light_blue.xml:#204285F4
panel_bg | lists | M12 | #2F3030 | Older panel: ground | 古いパネル: 地 | drawable/layout_bg.xml:#CCCCCC drawable/layout_bg_dark.xml:#2F3030 drawable/layout_bg_dark_solid.xml:#2F3030 drawable/layout_bg_solid.xml:#F0F0F0
divider_gradient | lists | L | #ffcdcdcd | Divider of older lists | 古いリストの区切り線 | drawable/divider.xml:#ffcdcdcd
list_note | text | D | #bebebe | Narrow list row: note | 幅の狭いリスト行: 注記 | layout/list_item_single_line_descrition_narrow.xml:#bebebe
search_banner_icon | text | A | #ec572e | Search banner: icon | 検索の案内: アイコン | layout/search_banner_list_item.xml:#ec572e layout/search_more_list_item.xml:#ec572e
fade_light | nav | G02 | #05ffffff | Route sheet: fade at the edge | 経路シート: 端のぼかし | drawable/route_info_trans_gradient_left_light.xml:#05ffffff drawable/route_info_trans_gradient_light.xml:#05ffffff drawable/route_info_trans_gradient_dark.xml:#05222526 drawable/route_info_trans_gradient_left_dark.xml:#05222526
chart_label_bg | charts | G90 | #E5FFFFFF | Chart label: ground | グラフのラベル: 地 | drawable/chart_label_background_light.xml:#E5FFFFFF
chart_label_frame | charts | A16 | #19000000 | Chart label: frame | グラフのラベル: 枠 | drawable/chart_label_background_light.xml:#19000000
chart_marker | charts | D | #808080 | Chart marker: frame | グラフのマーカー: 枠 | drawable/chart_marker_background_dark.xml:#808080 drawable/chart_marker_background_light.xml:#808080
recording_tick | controls | A | #007EB3 | Recording interval slider: tick | 記録間隔のスライダー: 目盛り | layout/trip_recording_starting_fragment.xml:#007EB3
recording_track | controls | A30 | #4D007EB3 | Recording interval slider: track | 記録間隔のスライダー: 溝 | layout/trip_recording_starting_fragment.xml:#4D007EB3
star_map_icon | text | I | #5f6e7c | Star map: icons and labels | 星図: アイコンとラベル | layout/fragment_star_map.xml:#5f6e7c
star_map_title | text | T | #000000 | Star map: title | 星図: 見出し | layout/fragment_star_map.xml:#000000
star_map_action | text | A | #4285F4 | Star map: action | 星図: 操作 | layout/fragment_star_map.xml:#4285F4
star_map_ripple | text | A10 | #154285F4 | Star map: press ripple | 星図: 押したときの波紋 | layout/fragment_star_map.xml:#154285F4
astro_card_value | text | A | #FF9800 | Visibility card: value | 見え方のカード: 値 | layout/astro_visibility_card.xml:#FF9800
"""

def parse():
    table = re.sub(r"\| M(\d\d) \|", r"| X\1 |", T)  # “M” is two characters with its number: read it as X
    src = open("OsmAnd/res/values/colors.xml", encoding="utf-8").read()
    cols = dict(re.findall(r'<color name="([^"]+)">([^<]+)</color>', src))

    def literal(name):
        v = cols[name].strip()
        if v.startswith("@color/"):
            return literal(v[7:])
        h = v.lstrip("#")
        if len(h) == 3:
            h = "FF" + "".join(c * 2 for c in h)
        elif len(h) == 4:
            h = "".join(c * 2 for c in h)
        elif len(h) == 6:
            h = "FF" + h
        return "0x" + h.upper()

    simple = {"G": "GROUND", "T": "TEXT", "D": "DIM", "A": "ACCENT", "I": "ICON", "L": "LINE", "O": "ON_ACCENT",
              "K": "KEEP"}
    scaled = {"G": "GROUND_ALPHA", "T": "TEXT_ALPHA", "A": "ACCENT_ALPHA", "X": "MIX"}
    roles, keys, fed = [], set(), {}
    for line in table.strip().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        section, names, rule, en, ja = [p.strip() for p in line.split("|")]
        assert section in SECTIONS, line
        if "=" in names:
            key, rest = [p.strip() for p in names.split("=")]
            res = rest.split()
        else:
            key, res = names, [names + "_dark", names + "_light"]
        assert key not in keys, "twice: " + key
        keys.add(key)
        for r in res:
            assert r in cols, "no such colour: " + r
            assert r not in fed, "%s is fed by %s and %s" % (r, fed[r], key)
            fed[r] = key
        m = re.fullmatch(r"([GTDAILOKX])(\d\d)?", rule)
        assert m, "rule? " + line
        kind, arg = m.group(1), m.group(2)
        if arg:
            rname, f, lit = scaled[kind], int(arg) / 100.0, "0"
        else:
            rname, f, lit = simple[kind], 0.0, (literal(res[0]) if kind == "K" else "0")
        roles.append((key, section, rname, f, lit, res, en, ja))
    lits = []
    for line in LIT.strip().splitlines():
        key, section, rule, default, en, ja, targets = [p.strip() for p in line.split("|")]
        assert section in SECTIONS, line
        rule = re.sub(r"^M(\d\d)$", r"X\1", rule)
        m = re.fullmatch(r"([GTDAILOKX])(\d\d)?", rule)
        assert m, "rule? " + line
        kind, arg = m.group(1), m.group(2)
        rname, f = (scaled[kind], int(arg) / 100.0) if arg else (simple[kind], 0.0)
        h = default.lstrip("#")
        h = "FF" + h if len(h) == 6 else h
        name = "chizu_lit_" + key
        assert "lit_" + key not in keys
        roles.append(("lit_" + key, section, rname, f, ("0x" + h.upper()) if kind == "K" else "0", [name], en, ja))
        lits.append((name, default, [t.split(":") for t in targets.split()]))
    return roles, lits

def move_literals(lits):
    """Replaces each listed literal by its colour resource; a file already moved is left as it is."""
    moved = 0
    for name, default, targets in lits:
        for path, literal in targets:
            full = "OsmAnd/res/" + path
            text = open(full, encoding="utf-8").read()
            # a literal inside a tools: attribute is design-time only and stays
            new, n = re.subn(r'(?<!tools:)(\b[\w:]+=")%s(")' % re.escape(literal), lambda m: m.group(1) + "@color/" + name + m.group(2), text)
            if n:
                open(full, "w", encoding="utf-8").write(new)
                moved += n
            else:
                assert "@color/" + name in text, "%s: neither %s nor %s" % (full, literal, name)
    x = ['<?xml version="1.0" encoding="utf-8"?>',
         "<!-- GENERATED by tools/chizu/gen_roles.py: the colours upstream wrote as literals, each fed from a role.",
         "     In the default values/ only: a qualified copy would beat the run-time loader. -->", "<resources>"]
    for name, default, _ in lits:
        x.append('\t<color name="%s">%s</color>' % (name, default))
    x += ["</resources>", ""]
    open("OsmAnd/res/values/chizu_lit_colors.xml", "w", encoding="utf-8").write("\n".join(x))
    return moved

def write(roles):
    j = ["package net.osmand.plus.chizu;", "", "import net.osmand.plus.R;", "",
         "/** GENERATED by tools/chizu/gen_roles.py — edit the table there, never this file. */",
         "final class ChizuRoleTable {", "", "\tprivate ChizuRoleTable() {", "\t}", "",
         "\tstatic final ChizuRoles.Role[] ALL = {"]
    for key, section, rname, f, lit, res, en, ja in roles:
        j.append('\t\tnew ChizuRoles.Role("%s", "%s", R.string.chizu_role_%s, ChizuRoles.Rule.%s, %sf, %s,' %
                 (key, section, key, rname, ("%.2f" % f), lit))
        j.append("\t\t\t\tnew int[] {%s})," % ", ".join("R.color." + r for r in res))
    j += ["\t};", "}", ""]
    open("OsmAnd/src/net/osmand/plus/chizu/ChizuRoleTable.java", "w", encoding="utf-8").write("\n".join(j))
    esc = lambda t: t.replace("&", "&amp;").replace("'", "\\'")
    for path, idx in (("OsmAnd/res/values/chizu_role_strings.xml", 6), ("OsmAnd/res/values-ja/chizu_role_strings.xml", 7)):
        x = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- GENERATED by tools/chizu/gen_roles.py: the labels of the item colour roles -->", "<resources>"]
        for r in roles:
            x.append('\t<string name="chizu_role_%s">%s</string>' % (r[0], esc(r[idx])))
        x += ["</resources>", ""]
        open(path, "w", encoding="utf-8").write("\n".join(x))

if __name__ == "__main__":
    roles, lits = parse()
    print("literals moved now:", move_literals(lits))
    write(roles)
    by = {}
    for r in roles:
        by[r[1]] = by.get(r[1], 0) + 1
    print(len(roles), "roles,", sum(len(r[5]) for r in roles), "colour names fed;", by)
