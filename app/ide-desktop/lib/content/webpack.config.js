const CopyWebpackPlugin = require('copy-webpack-plugin')
const CompressionPlugin = require('compression-webpack-plugin')
const path = require('path')
const webpack = require('webpack')
const utils = require('../../utils')
const BUILD_INFO = require('../../build.json')

const thisPath = path.resolve(__dirname)

const child_process = require('child_process')
function git(command) {
    return child_process.execSync(`git ${command}`, { encoding: 'utf8' }).trim()
}

const output_path = utils.require_env('ENSO_BUILD_GUI')
const wasm_path = utils.require_env('ENSO_BUILD_GUI_WASM')
const js_glue_path = utils.require_env('ENSO_BUILD_GUI_JS_GLUE')
const assets_path = utils.require_env('ENSO_BUILD_GUI_ASSETS')

// scala-parser.js is compiled from Scala code, so no source map is available for it.
const IGNORE_SOURCE_MAPS = [/scala-parser\.js/]

// Load source maps for JS and TS files, so we will have an accurate source available in DevTools.
// `ignored` is a list of regexes that are matched against file URL to ignore missing source
// maps for certain files.
const sourceMapLoader = ignored => ({
    loader: 'source-map-loader',
    options: {
        filterSourceMappingUrl: (url, _resourcePath) => {
            for (let regexp of ignored) {
                if (regexp.test(url)) return 'skip'
            }
            return true
        },
    },
})

module.exports = {
    entry: {
        index: path.resolve(thisPath, 'src', 'index.ts'),
        wasm_imports: './src/wasm_imports.js',
    },
    output: {
        path: path.resolve(output_path, 'assets'),
        filename: '[name].js',
        libraryTarget: 'umd',
    },
    node: {
        fs: 'empty',
    },
    plugins: [
        new CompressionPlugin(),
        new CopyWebpackPlugin([
            path.resolve(thisPath, 'src', 'index.html'),
            path.resolve(thisPath, 'src', 'run.js'),
            path.resolve(thisPath, 'src', 'style.css'),
            path.resolve(thisPath, 'src', 'docsStyle.css'),
            assets_path,
            wasm_path,
        ]),
        new webpack.DefinePlugin({
            GIT_HASH: JSON.stringify(git('rev-parse HEAD')),
            GIT_STATUS: JSON.stringify(git('status --short --porcelain')),
            BUILD_INFO: JSON.stringify(BUILD_INFO),
        }),
    ],
    devtool: 'eval-source-map',
    devServer: {
        publicPath: '/assets/',
        historyApiFallback: {
            index: '/assets/',
        },
    },
    resolve: {
        alias: {
            wasm_rust_glue$: js_glue_path,
        },
        extensions: ['.ts', '.js'],
    },
    performance: {
        hints: false,
    },
    mode: 'none',
    module: {
        rules: [
            {
                test: /\.ya?ml$/,
                type: 'json',
                use: 'yaml-loader',
            },
            {
                test: /\.tsx?/,
                use: 'ts-loader',
                exclude: /node_modules/,
            },
            {
                test: /\.html$/i,
                loader: 'html-loader',
            },
            {
                test: [/\.js$/, /\.tsx?$/],
                enforce: 'pre',
                loader: sourceMapLoader(IGNORE_SOURCE_MAPS),
            },
        ],
    },
}
