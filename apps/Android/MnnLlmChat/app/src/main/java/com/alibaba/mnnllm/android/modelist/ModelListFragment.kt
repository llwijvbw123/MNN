// Created by ruoyi.sjd on 2025/1/13.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.modelist

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.mls.api.ModelItem
import com.alibaba.mnnllm.android.MainActivity
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.mainsettings.MainSettingsActivity
import com.alibaba.mnnllm.android.utils.CrashUtil
import com.alibaba.mnnllm.android.utils.PreferenceUtils.isFilterDownloaded
import com.alibaba.mnnllm.android.utils.PreferenceUtils.setFilterDownloaded
import com.alibaba.mnnllm.android.utils.RouterUtils.startActivity
import com.blankj.utilcode.util.EncryptUtils
import com.blankj.utilcode.util.GsonUtils
import com.google.gson.reflect.TypeToken
import java.io.*

class ModelListFragment : Fragment(), ModelListContract.View {
    private lateinit var modelListRecyclerView: RecyclerView

    override var adapter: ModelListAdapter? = null
        private set
    private var modelListPresenter: ModelListPresenter? = null
    private val hfModelItemList: MutableList<ModelItem> = mutableListOf()

    private lateinit var modelListLoadingView: View
    private lateinit var modelListErrorView: View

    private var modelListErrorText: TextView? = null

    private var filterDownloaded = false
    private var filterQuery = ""

    private fun setupSearchView(menu: Menu) {
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView?
        if (searchView != null) {
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    filterQuery = query
                    adapter!!.setFilter(query, filterDownloaded)
                    return false
                }

                override fun onQueryTextChange(query: String): Boolean {
                    filterQuery = query
                    adapter!!.setFilter(query, filterDownloaded)
                    return true
                }
            })
            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    // SearchView is expanded
                    Log.d("SearchView", "SearchView expanded")
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    // SearchView is collapsed
                    Log.d("SearchView", "SearchView collapsed")
                    adapter!!.unfilter()

                    return true
                }
            })
        }
    }

    private val menuProvider: MenuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            // Inflate your menu resource here
            menuInflater.inflate(R.menu.menu_main, menu)
            setupSearchView(menu)
            val issueMenu = menu.findItem(R.id.action_github_issue)
            issueMenu.setOnMenuItemClickListener { item: MenuItem? ->
                if (activity != null) {
                    (activity as MainActivity).onReportIssue(null)
                }
                true
            }

            val filterDownloadedMenu = menu.findItem(R.id.action_filter_downloaded)
            filterDownloadedMenu.setChecked(isFilterDownloaded(context))
            filterDownloadedMenu.setOnMenuItemClickListener {
                filterDownloaded = isFilterDownloaded(
                    context
                )
                filterDownloaded = !filterDownloaded
                setFilterDownloaded(context, filterDownloaded)
                filterDownloadedMenu.setChecked(filterDownloaded)
                adapter!!.setFilter(filterQuery, filterDownloaded)
                true
            }
            val settingsMenu = menu.findItem(R.id.action_settings)
            settingsMenu.setOnMenuItemClickListener {
                if (activity != null) {
                    startActivity(activity!!, MainSettingsActivity::class.java)
                }
                true
            }

            val starGithub = menu.findItem(R.id.action_star_project)
            starGithub.setOnMenuItemClickListener { item: MenuItem? ->
                if (activity != null) {
                    (activity as MainActivity).onStarProject(null)
                }
                true
            }
            val reportCrashMenu = menu.findItem(R.id.action_report_crash)
            reportCrashMenu.setOnMenuItemClickListener {
                if (CrashUtil.hasCrash()) {
                    CrashUtil.shareLatestCrash(context!!)
                }
                true
            }
            val loadLocalMenu = menu.findItem(R.id.action_loadlocal)
            loadLocalMenu.setOnMenuItemClickListener { item: MenuItem? ->
                if (activity != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11 及以上版本使用 SAF
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        startActivityForResult(intent, 1001)
                    } else {
                        // 老版本请求权限
                        if (ContextCompat.checkSelfPermission(
                                activity!!,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            )
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(
                                activity!!,
                                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                                1001
                            )
                        } else {
//                            copyFolderFromOldSDCard();
                        }
                    }
                }
                true
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            return true
        }

        override fun onPrepareMenu(menu: Menu) {
            super<MenuProvider>.onPrepareMenu(menu)
            val menuResumeAllDownlods = menu.findItem(R.id.action_resume_all_downloads)
            menuResumeAllDownlods.setVisible(modelListPresenter!!.unfinishedDownloadCount > 0)
            menuResumeAllDownlods.setOnMenuItemClickListener { item: MenuItem? ->
                modelListPresenter!!.resumeAllDownloads()
                true
            }
            val reportCrashMenu = menu.findItem(R.id.action_report_crash)
            reportCrashMenu.isVisible = CrashUtil.hasCrash()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_modellist, container, false)
        modelListRecyclerView = view.findViewById(R.id.model_list_recycler_view)
        modelListLoadingView = view.findViewById(R.id.model_list_loading_view)
        modelListErrorView = view.findViewById(R.id.model_list_failed_view)
        modelListErrorText = modelListErrorView.findViewById(R.id.tv_error_text)
        modelListErrorView.setOnClickListener {
            modelListPresenter!!.load()
        }
        modelListRecyclerView.setLayoutManager(
            LinearLayoutManager(
                context,
                LinearLayoutManager.VERTICAL,
                false
            )
        )
        adapter = ModelListAdapter(hfModelItemList)

        modelListRecyclerView.setAdapter(adapter)
        modelListPresenter = ModelListPresenter(requireContext(), this)
        adapter!!.setModelListListener(modelListPresenter)
        filterDownloaded = isFilterDownloaded(context)
        adapter!!.setFilter(filterQuery, filterDownloaded)
        modelListPresenter!!.onCreate()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        modelListPresenter!!.onDestroy()
    }

    override fun onListAvailable() {
        modelListErrorView.visibility = View.GONE
        modelListLoadingView.visibility = View.GONE
        modelListRecyclerView.visibility = View.VISIBLE
    }

    override fun onLoading() {
        if (adapter!!.itemCount > 0) {
            return
        }
        modelListErrorView.visibility = View.GONE
        modelListLoadingView.visibility = View.VISIBLE
        modelListRecyclerView.visibility = View.GONE
    }

    override fun onListLoadError(error: String?) {
        if (adapter!!.itemCount > 0) {
            return
        }
        modelListErrorText!!.text = getString(R.string.loading_failed_click_tor_retry, error)
        modelListErrorView.visibility = View.VISIBLE
        modelListLoadingView.visibility = View.GONE
        modelListRecyclerView.visibility = View.GONE
    }

    override fun runModel(absolutePath: String?, modelId: String?) {
        (activity as MainActivity).runModel(absolutePath, modelId, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                val treeUri = data.data
                if (treeUri != null) {

                    // 持久化访问权限
                    requireActivity().contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    // 开始复制文件夹
                    copyFolderFromExternal(treeUri)
                }
            }
        }
    }
    private fun copyFolderFromExternal(sourceTreeUri: Uri) {
        val contentResolver = requireActivity().contentResolver

        val sha256: String = checkFileExistsSha256(contentResolver, sourceTreeUri, "llm.mnn") ?: return
        val destinationDir = File(requireActivity().getExternalFilesDir(null).toString() + "/" + sha256)
        if (!destinationDir!!.exists()) {
            destinationDir!!.mkdirs()
        } else {
            Toast.makeText(context, "模型已导入,将进行覆盖!", Toast.LENGTH_SHORT).show()
        }
        if (destinationDir != null) {
            try {
                copyDirectory(contentResolver, sourceTreeUri, destinationDir)

                val sharedPreferences = requireActivity().getSharedPreferences("LOCAL_IMPORT", Context.MODE_PRIVATE)
                val listStr = sharedPreferences.getString("local_import", "[]")
                val list =
                    GsonUtils.fromJson<MutableList<String>>(listStr, object : TypeToken<List<String?>?>() {}.type)
                val editor = sharedPreferences.edit()
                if (!list.contains(sha256)) {
                    list.add(sha256)
                }
                editor.putString("local_import", GsonUtils.toJson(list))
                editor.apply()
                Toast.makeText(context, "模型导入完成", Toast.LENGTH_SHORT).show()
                requireActivity().runOnUiThread { modelListPresenter?.refreshWithCache() }
            } catch (e: IOException) {
                destinationDir.delete()
                e.printStackTrace()
                Toast.makeText(context, "模型导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun checkFileExistsSha256(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        targetFileName: String
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            sourceUri,
            DocumentsContract.getTreeDocumentId(sourceUri)
        )
        contentResolver.query(childrenUri, null, null, null, null).use { cursor ->
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range") val displayName =
                        cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    @SuppressLint("Range") val documentId =
                        cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    if (targetFileName == displayName) {
                        try {
                            val childUri =
                                DocumentsContract.buildDocumentUriUsingTree(sourceUri, documentId)
                            val inputStream = contentResolver.openInputStream(childUri)
                            val read = ByteArray(1024 * 1024)
                            if (inputStream != null) {
                                inputStream.read(read, 0, 1024 * 1024)
                                return EncryptUtils.encryptSHA256ToString(read)
                            }
                        } catch (e: IOException) {
                            throw RuntimeException(e)
                        }
                        return null
                    }
                }
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun copyDirectory(contentResolver: ContentResolver, sourceUri: Uri, destinationDir: File) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            sourceUri,
            DocumentsContract.getTreeDocumentId(sourceUri)
        )
        contentResolver.query(childrenUri, null, null, null, null).use { cursor ->
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range") val documentId =
                        cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    @SuppressLint("Range") val mimeType =
                        cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
                    @SuppressLint("Range") val displayName =
                        cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(sourceUri, documentId)

                    if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                        // 子文件夹
                        val newDir = File(destinationDir, displayName)
                        if (!newDir.exists()) {
                            newDir.mkdirs()
                        }
                        copyDirectory(contentResolver, childUri, newDir)
                    } else {
                        // 文件
                        val newFile = File(destinationDir, displayName)
                        contentResolver.openInputStream(childUri).use { inputStream ->
                            FileOutputStream(newFile).use { outputStream ->
                                if (inputStream != null) {
                                    copyStream(inputStream, outputStream)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(4096)
        var bytesRead: Int
        while ((input.read(buffer).also { bytesRead = it }) != -1) {
            output.write(buffer, 0, bytesRead)
        }
    }
}
